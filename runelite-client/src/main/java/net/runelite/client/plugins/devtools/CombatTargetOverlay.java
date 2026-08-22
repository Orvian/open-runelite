/*
 * Copyright (c) 2026, Open RuneLite contributors
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.devtools;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Draws animation, pose and spotanim ids on the actors of the current fight only - what the local player
 * is fighting, and anything fighting it. The "NPCs" toggle draws the same information on every NPC in the
 * scene, which is unreadable at a boss.
 */
class CombatTargetOverlay extends Overlay
{
	private static final Color TARGET_COLOR = Color.CYAN;
	private static final Color ATTACKER_COLOR = Color.ORANGE;

	/**
	 * How many past animations to keep per actor. Enough to read an attack rotation at a glance without
	 * the text growing taller than the model it is drawn over.
	 */
	private static final int HISTORY_SIZE = 4;

	private final Map<Actor, Deque<int[]>> animationHistory = new WeakHashMap<>();

	private final Client client;
	private final DevToolsPlugin plugin;

	@Inject
	CombatTargetOverlay(Client client, DevToolsPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.getCombatTarget().isActive())
		{
			return null;
		}

		final Player local = client.getLocalPlayer();
		if (local == null)
		{
			return null;
		}

		final Set<Actor> drawn = new HashSet<>();

		final Actor target = local.getInteracting();
		if (target != null && drawn.add(target))
		{
			trackAnimation(target);
			OverlayUtil.renderActorOverlay(graphics, target, describe(target) + describeHistory(target), TARGET_COLOR);
		}

		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc.getInteracting() == local && drawn.add(npc))
			{
				trackAnimation(npc);
				OverlayUtil.renderActorOverlay(graphics, npc, describe(npc) + describeHistory(npc), ATTACKER_COLOR);
			}
		}

		return null;
	}

	/**
	 * Records animation changes as they are drawn. Fast attacks are over before the eye can read the
	 * current id, so the last few are kept with the tick they started on.
	 */
	private void trackAnimation(Actor actor)
	{
		final int animation = actor.getAnimation();
		if (animation == -1)
		{
			return;
		}

		final Deque<int[]> history = animationHistory.computeIfAbsent(actor, k -> new ArrayDeque<>());
		final int[] last = history.peekLast();
		if (last != null && last[0] == animation)
		{
			return;
		}

		history.addLast(new int[]{animation, client.getTickCount()});
		while (history.size() > HISTORY_SIZE)
		{
			history.removeFirst();
		}
	}

	private String describeHistory(Actor actor)
	{
		final Deque<int[]> history = animationHistory.get(actor);
		if (history == null || history.size() < 2)
		{
			return "";
		}

		final StringBuilder sb = new StringBuilder(" [");
		final int now = client.getTickCount();
		boolean first = true;
		for (int[] entry : history)
		{
			sb.append(first ? "" : " ").append(entry[0]).append('@').append(entry[1] - now).append('t');
			first = false;
		}

		return sb.append(']').toString();
	}

	private String describe(Actor actor)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append(actor.getName());

		if (actor instanceof NPC)
		{
			sb.append(" (ID: ").append(((NPC) actor).getId()).append(')');
		}

		sb.append(" (A: ").append(actor.getAnimation())
			.append(") (P: ").append(actor.getPoseAnimation())
			.append(')');

		final StringBuilder spotanims = new StringBuilder();
		for (ActorSpotAnim spotAnim : actor.getSpotAnims())
		{
			spotanims.append(spotanims.length() == 0 ? "" : ",").append(spotAnim.getId());
		}

		if (spotanims.length() > 0)
		{
			sb.append(" (G: ").append(spotanims).append(')');
		}

		final int healthRatio = actor.getHealthRatio();
		final int healthScale = actor.getHealthScale();
		if (healthRatio >= 0 && healthScale > 0)
		{
			sb.append(" (HP: ").append(healthRatio * 100 / healthScale).append("%)");
		}

		return sb.toString();
	}
}
