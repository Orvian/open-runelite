/*
 * Copyright (c) 2026, Orvian
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

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.inject.Inject;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.Getter;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.GraphicsObject;
import net.runelite.api.TileObject;
import net.runelite.api.HeadIcon;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.AreaSoundEffectPlayed;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.SoundEffectPlayed;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * A per-tick log of the events that make up a fight, in a form that is meant to be read (or pasted
 * somewhere) afterwards rather than watched live.
 * <p>
 * Two things keep it readable. Only actors involved in the fight are logged - the local player, whatever
 * it is fighting, and anything fighting it - unless "All actors" is ticked. And each new animation on the
 * fight target opens a numbered <b>step</b>, so the events an attack is made of (the wind-up animation,
 * the projectile, the spotanim, the hitsplat) are grouped under the step that caused them.
 */
class ActionLog extends DevToolsFrame
{
	private static final int MAX_LOG_ENTRIES = 10_000;

	/**
	 * Ticks without a relevant event before the current fight is considered over.
	 */
	private static final int FIGHT_TIMEOUT_TICKS = 15;

	private static final Map<Integer, String> HITSPLAT_NAMES = DevToolsPlugin.loadFieldNames(HitsplatID.class);
	private static final Map<Integer, String> VARBIT_NAMES = DevToolsPlugin.loadFieldNames(VarbitID.class);

	/**
	 * How far from the player an NPC or ground graphic has to be before it is assumed to be scenery
	 * rather than part of the fight.
	 */
	private static final int NEARBY_TILES = 15;

	/**
	 * Consecutive ticks of change after which a varbit is treated as a clock and muted.
	 */
	private static final int CHATTY_VARBIT_TICKS = 3;

	/**
	 * Spotanim id used as a placeholder rather than an actual graphic.
	 */
	private static final int NULL_GRAPHIC = 65535;

	@Getter
	private enum Category
	{
		ANIMATION("Animations", true),
		SPOTANIM("Spotanims", true),
		PROJECTILE("Projectiles", true),
		HITSPLAT("Hitsplats", true),
		INTERACTING("Targeting", true),
		MOVEMENT("Movement", true),
		HEALTH("Target HP", true),
		OVERHEAD("Overhead", true),
		TRANSFORM("NPC transform", true),
		GROUND_GFX("Ground gfx", true),
		VARBIT("Varbits", false),
		CHAT("Chat", false),
		SOUND("Sounds", false),
		ADDS("NPC spawns", false),
		OBJECTS("Scene objects", false),
		POSE("Pose anims", false),
		IDLE("Idle resets", false),
		ALL_ACTORS("All actors", false);

		private final String name;
		private final JCheckBox checkBox;

		Category(String name, boolean on)
		{
			this.name = name;
			checkBox = new JCheckBox(name, on);
		}

		boolean isEnabled()
		{
			return checkBox.isSelected();
		}
	}

	/**
	 * What an event happened to, which is all the summary needs to know about actors.
	 */
	private enum Who
	{
		TARGET,
		LOCAL,
		OTHER
	}

	private enum Kind
	{
		ANIM,
		SPOTANIM,
		PROJECTILE,
		HIT
	}

	/**
	 * A logged event in a form that can be grouped, as opposed to the text line shown in the window.
	 */
	private static class Ev
	{
		private final int tick;
		private final int cycle;
		private final Kind kind;
		private final Who who;
		private final int id;
		private final int amount;

		Ev(int tick, int cycle, Kind kind, Who who, int id, int amount)
		{
			this.tick = tick;
			this.cycle = cycle;
			this.kind = kind;
			this.who = who;
			this.id = id;
			this.amount = amount;
		}
	}

	private final Client client;
	private final EventBus eventBus;

	/**
	 * A text area rather than a component per line: a boss fight produces thousands of entries, and a
	 * label each means the event dispatch thread relays out the whole log on every event, which is felt
	 * as stutter in the game itself.
	 */
	private final JTextArea area = new JTextArea();
	private final JScrollPane scroller;
	private final JTextField filter = new JTextField();

	/**
	 * Every captured line. What the area shows is this, minus whatever the filter excludes.
	 */
	private final Deque<String> lines = new ArrayDeque<>();

	/**
	 * Lines captured since the last flush. The client thread only ever appends here; the event dispatch
	 * thread drains it once a tick, so a burst of events costs one repaint rather than twenty.
	 */
	private final List<String> pending = new ArrayList<>();
	private final List<Ev> events = new ArrayList<>();

	/**
	 * Projectiles are posted every frame they move, so only the first sighting of each is logged.
	 */
	private final Set<Projectile> seenProjectiles = Collections.newSetFromMap(new WeakHashMap<>());
	private final Map<Actor, Integer> lastAnimations = new WeakHashMap<>();
	private final Map<Actor, Integer> lastPoses = new WeakHashMap<>();
	private final Map<Actor, Integer> animationStartTicks = new WeakHashMap<>();

	/**
	 * Spotanims are reported as the actor's whole set, and one event is posted per spotanim added, so
	 * the last set is kept to log only what is new, and a tick's worth of additions is logged as one line.
	 */
	private final Map<Actor, Set<Integer>> lastSpotAnims = new WeakHashMap<>();
	private final Map<Actor, List<Integer>> pendingSpotAnims = new WeakHashMap<>();
	private final Map<Actor, int[]> pendingSpotAnimStamps = new WeakHashMap<>();

	private final Map<Integer, List<WorldPoint>> pendingGroundGfx = new LinkedHashMap<>();
	private final Map<Integer, int[]> pendingGroundGfxStamps = new HashMap<>();
	private final Map<Integer, Integer> pendingGroundGfxLeads = new HashMap<>();

	private final Set<String> soundsThisTick = new HashSet<>();

	private Actor lastInteractSource;
	private Actor lastInteractTarget;
	private boolean targetNamed;

	private WorldPoint lastLocalPosition;
	private WorldPoint lastTargetPosition;
	private int lastTargetHealth = -1;
	private HeadIcon lastTargetOverhead;
	private final Map<Integer, Integer> lastVarbits = new HashMap<>();
	private final Map<Integer, Integer> varbitChangeTicks = new HashMap<>();
	private final Map<Integer, Integer> varbitRunLengths = new HashMap<>();
	private final Set<Integer> mutedVarbits = new HashSet<>();

	private boolean recording = true;

	/**
	 * Whether the view should follow new entries. Turned off as soon as the user scrolls up to read
	 * back, and on again when they return to the bottom.
	 */
	private boolean followTail = true;

	private Actor target;
	private int fightStartTick = -1;
	private int fightStartCycle = -1;
	private int lastEventTick = -1;
	private int step;
	private int damageDealt;
	private int damageTaken;

	@Inject
	ActionLog(Client client, EventBus eventBus)
	{
		this.client = client;
		this.eventBus = eventBus;

		setTitle("RuneLite Action Log");
		setLayout(new BorderLayout());

		area.setEditable(false);
		area.setLineWrap(false);
		area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

		final JScrollPane trackerScroller = new JScrollPane(area);
		this.scroller = trackerScroller;
		trackerScroller.setPreferredSize(new Dimension(720, 420));

		final JScrollBar vertical = trackerScroller.getVerticalScrollBar();
		vertical.addAdjustmentListener(e ->
		{
			if (e.getValueIsAdjusting() || vertical.getValueIsAdjusting())
			{
				// The user is dragging, so follow the tail only if they dragged back down to it
				followTail = isAtBottom(vertical);
			}
		});

		add(trackerScroller, BorderLayout.CENTER);

		final JPanel filterRow = new JPanel();
		filterRow.setLayout(new BorderLayout());
		filterRow.add(new JLabel(" Filter: "), BorderLayout.WEST);
		filterRow.add(filter, BorderLayout.CENTER);
		filter.setToolTipText("Show only lines containing this text - applies to lines already captured");
		filter.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				rebuild();
			}
		});
		add(filterRow, BorderLayout.NORTH);

		final JPanel filters = new JPanel();
		filters.setLayout(new GridLayout(0, 4, 2, 2));
		for (Category c : Category.values())
		{
			filters.add(c.getCheckBox());
		}

		final JPanel buttons = new JPanel();
		buttons.setLayout(new FlowLayout());

		final JPanel trackerOpts = new JPanel();
		trackerOpts.setLayout(new BorderLayout());
		trackerOpts.add(filters, BorderLayout.CENTER);
		trackerOpts.add(buttons, BorderLayout.SOUTH);

		final JButton startStopBtn = new JButton("Stop logging");
		startStopBtn.addActionListener(e ->
		{
			recording = !recording;
			startStopBtn.setText(recording ? "Stop logging" : "Start logging");
			appendLine(recording ? "=== logging started ===" : "=== logging stopped ===");
		});
		buttons.add(startStopBtn);

		final JButton copyBtn = new JButton("Copy");
		copyBtn.addActionListener(e -> Toolkit.getDefaultToolkit()
			.getSystemClipboard()
			.setContents(new StringSelection(area.getText()), null));
		buttons.add(copyBtn);

		final JButton summaryBtn = new JButton("Summary");
		summaryBtn.addActionListener(e -> summarise());
		buttons.add(summaryBtn);

		final JButton clearBtn = new JButton("Clear");
		clearBtn.addActionListener(e ->
		{
			lines.clear();
			events.clear();
			synchronized (pending)
			{
				pending.clear();
			}
			area.setText("");
		});
		buttons.add(clearBtn);

		add(trackerOpts, BorderLayout.SOUTH);

		pack();
	}

	private void addLine(String line)
	{
		if (!recording)
		{
			return;
		}

		appendLine(line);
	}

	private void appendLine(String line)
	{
		synchronized (pending)
		{
			pending.add(line);
		}
	}

	/**
	 * Moves everything captured since the last call into the view, as one update.
	 */
	private void flushPending()
	{
		final List<String> batch;
		synchronized (pending)
		{
			if (pending.isEmpty())
			{
				return;
			}

			batch = new ArrayList<>(pending);
			pending.clear();
		}

		SwingUtilities.invokeLater(() ->
		{
			final StringBuilder appended = new StringBuilder();
			boolean trimmed = false;
			for (String line : batch)
			{
				lines.addLast(line);
				if (lines.size() > MAX_LOG_ENTRIES)
				{
					lines.removeFirst();
					trimmed = true;
				}

				if (matchesFilter(line))
				{
					appended.append(line).append('\n');
				}
			}

			if (trimmed)
			{
				rebuild();
			}
			else if (appended.length() > 0)
			{
				area.append(appended.toString());
				scrollToTailIfFollowing();
			}
		});
	}

	private boolean matchesFilter(String line)
	{
		final String needle = filter.getText();
		return needle.isEmpty() || line.toLowerCase().contains(needle.toLowerCase());
	}

	/**
	 * Redraws the whole view from the captured lines, which is what makes the filter apply to entries
	 * that were logged before it was typed.
	 */
	private void rebuild()
	{
		final StringBuilder sb = new StringBuilder();
		for (String line : lines)
		{
			if (matchesFilter(line))
			{
				sb.append(line).append('\n');
			}
		}

		area.setText(sb.toString());
		scrollToTailIfFollowing();
	}

	private void scrollToTailIfFollowing()
	{
		if (!followTail)
		{
			return;
		}

		SwingUtilities.invokeLater(() ->
		{
			final JScrollBar bar = scroller.getVerticalScrollBar();
			bar.setValue(bar.getMaximum());
		});
	}

	private static boolean isAtBottom(JScrollBar bar)
	{
		return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - bar.getUnitIncrement();
	}

	/**
	 * Logs one event of the fight, prefixed with its tick offset from the start of the fight and the step
	 * it belongs to.
	 */
	private void addEvent(String text)
	{
		addEvent(text, null, null, 0, 0);
	}

	/**
	 * Logs one event of the fight, prefixed with its tick and game cycle offset from the start of the
	 * fight and the step it belongs to. A game cycle is 20ms, so the cycle offset is what orders events
	 * inside a single tick - which is the only way to read an attack that resolves faster than 600ms.
	 */
	private void addEvent(String text, Kind kind, Who who, int id, int amount)
	{
		addEventAt(client.getTickCount(), client.getGameCycle(), text, kind, who, id, amount);
	}

	private void addEventAt(int tick, int cycle, String text, Kind kind, Who who, int id, int amount)
	{
		if (fightStartTick < 0)
		{
			startFight(tick, cycle);
		}

		lastEventTick = tick;
		addLine(String.format("[t+%-3d c+%-4d s%-2d] %s", tick - fightStartTick, cycle - fightStartCycle, step, text));

		if (kind != null)
		{
			events.add(new Ev(tick, cycle, kind, who, id, amount));
			while (events.size() > MAX_LOG_ENTRIES)
			{
				events.remove(0);
			}
		}
	}

	/**
	 * Works out what we are actually fighting, once a tick. Interaction events alone are not enough:
	 * if the interaction is already established when the log is opened, or the boss keeps switching
	 * what it faces, no event arrives and every later event would be attributed to the wrong actor.
	 */
	private void resolveTarget()
	{
		final Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		Actor resolved = local.getInteracting();
		final WorldView worldView = client.getTopLevelWorldView();
		if ((resolved == null || resolved.isDead()) && worldView != null)
		{
			// Nothing being attacked, so fall back to whatever is attacking us
			resolved = null;
			for (NPC npc : worldView.npcs())
			{
				if (npc.getInteracting() == local && !npc.isDead())
				{
					resolved = npc;
					break;
				}
			}
		}

		if (resolved == null || resolved == target)
		{
			return;
		}

		target = resolved;
		lastTargetPosition = null;
		lastTargetHealth = -1;
		lastTargetOverhead = null;

		if (fightStartTick >= 0 && targetNamed)
		{
			addLine("=== fight target changed: " + describe(target) + " ===");
		}
		else
		{
			nameTarget();
		}
	}

	/**
	 * Names the fight target in the log the first time it is known, which is usually a tick or two after
	 * the first event of the fight.
	 */
	private void nameTarget()
	{
		if (targetNamed || target == null || fightStartTick < 0)
		{
			return;
		}

		targetNamed = true;
		lastTargetPosition = target.getWorldLocation();
		final int size = target instanceof NPC && ((NPC) target).getComposition() != null
			? ((NPC) target).getComposition().getSize() : 1;

		addLine("=== fight target: " + describe(target)
			+ (lastTargetPosition == null ? "" : " at " + format(lastTargetPosition))
			+ " size " + size + "x" + size
			+ " facing " + target.getCurrentOrientation() + " (" + compass(target.getCurrentOrientation()) + ")"
			+ " hp scale " + target.getHealthScale() + " ===");
	}

	/**
	 * Logs context that is only meaningful inside a fight. Unlike {@link #addEvent}, this never starts
	 * one - walking around or a varbit ticking over is not the beginning of a fight.
	 */
	private void addPassive(String text)
	{
		if (fightStartTick < 0)
		{
			return;
		}

		addEvent(text);
	}

	private Who who(Actor actor)
	{
		if (actor == target)
		{
			return Who.TARGET;
		}

		return actor == client.getLocalPlayer() ? Who.LOCAL : Who.OTHER;
	}

	private void startFight(int tick, int cycle)
	{
		fightStartTick = tick;
		fightStartCycle = cycle;
		lastEventTick = tick;
		step = 0;
		damageDealt = 0;
		damageTaken = 0;
		targetNamed = target != null;
		addLine("=== fight start: " + (target == null ? "(target not known yet)" : describe(target))
			+ " @ tick " + tick + " ===");

		final Player local = client.getLocalPlayer();
		if (local != null && local.getWorldLocation() != null)
		{
			lastLocalPosition = local.getWorldLocation();
			final WorldView worldView = client.getTopLevelWorldView();
			addLine("=== context: world " + client.getWorld() + ", you at " + format(lastLocalPosition)
				+ ", instance " + (worldView != null && worldView.isInstance()) + " ===");
		}
	}

	private void endFight()
	{
		if (fightStartTick < 0)
		{
			return;
		}

		addLine(String.format("=== fight end: %s @ tick %d (%dt, %d steps) dealt %d taken %d ===",
			describe(target), lastEventTick, lastEventTick - fightStartTick, step, damageDealt, damageTaken));
		summarise();
		addLine("");

		fightStartTick = -1;
		fightStartCycle = -1;
		lastEventTick = -1;
		target = null;
		lastAnimations.clear();
		lastPoses.clear();
		seenProjectiles.clear();
		targetNamed = false;
		lastLocalPosition = null;
		lastTargetPosition = null;
		lastTargetHealth = -1;
		lastTargetOverhead = null;
		lastVarbits.clear();
		varbitChangeTicks.clear();
		varbitRunLengths.clear();
		mutedVarbits.clear();
		pendingGroundGfx.clear();
		pendingGroundGfxStamps.clear();
		pendingGroundGfxLeads.clear();
		animationStartTicks.clear();
		soundsThisTick.clear();
		lastInteractSource = null;
		lastInteractTarget = null;
		lastSpotAnims.clear();
		pendingSpotAnims.clear();
		pendingSpotAnimStamps.clear();
		// so the next fight's summary is not polluted by this one
		events.clear();
	}


	/**
	 * Condenses the recorded fight into one block per distinct target animation: how often it happened,
	 * how far apart, and what followed it before the next animation started. This is the part worth
	 * pasting somewhere - it is the attack rotation, with ids, rather than a scrolling event feed.
	 */
	private void summarise()
	{
		if (events.isEmpty())
		{
			return;
		}

		// An "attack" is one target animation and everything that happened before the next one
		final List<Ev> attacks = new ArrayList<>();
		final Map<Integer, List<Ev>> followUps = new LinkedHashMap<>();
		final Map<Integer, List<Integer>> gaps = new LinkedHashMap<>();

		Ev current = null;
		for (Ev ev : events)
		{
			if (ev.kind == Kind.ANIM && ev.who == Who.TARGET)
			{
				if (current != null)
				{
					gaps.computeIfAbsent(current.id, k -> new ArrayList<>()).add(ev.tick - current.tick);
				}

				current = ev;
				attacks.add(ev);
				followUps.computeIfAbsent(ev.id, k -> new ArrayList<>());
			}
			else if (current != null)
			{
				followUps.get(current.id).add(new Ev(ev.tick - current.tick, ev.cycle - current.cycle,
					ev.kind, ev.who, ev.id, ev.amount));
			}
		}

		if (attacks.isEmpty())
		{
			return;
		}

		addLine("--- attack pattern: " + describe(target) + " ---");

		for (Map.Entry<Integer, List<Ev>> entry : followUps.entrySet())
		{
			final int animation = entry.getKey();
			int count = 0;
			for (Ev attack : attacks)
			{
				if (attack.id == animation)
				{
					count++;
				}
			}

			addLine("anim " + animation + "  x" + count + describeGaps(gaps.get(animation)));

			// Collapse the follow-ups of every occurrence of this animation into one line per id
			final Map<String, List<Ev>> byId = new LinkedHashMap<>();
			for (Ev follow : entry.getValue())
			{
				byId.computeIfAbsent(follow.kind + ":" + follow.id + ":" + follow.who, k -> new ArrayList<>()).add(follow);
			}

			for (List<Ev> group : byId.values())
			{
				final Ev first = group.get(0);
				final StringBuilder line = new StringBuilder("    -> ");

				switch (first.kind)
				{
					case PROJECTILE:
						line.append("projectile ").append(first.id);
						break;
					case SPOTANIM:
						line.append("spotanim ").append(first.id).append(" on ").append(first.who == Who.LOCAL ? "You" : "target");
						break;
					case HIT:
						line.append("hit ").append(first.who == Who.LOCAL ? "You" : "target").append(" avg ").append(averageAmount(group));
						break;
					case ANIM:
					default:
						line.append("anim ").append(first.id);
						break;
				}

				line.append("  x").append(group.size())
					.append(" at +").append(averageCycle(group)).append("c (+")
					.append(averageTick(group)).append("t)");
				addLine(line.toString());
			}
		}
	}

	private String describeGaps(List<Integer> gapsForAnimation)
	{
		if (gapsForAnimation == null || gapsForAnimation.isEmpty())
		{
			return "";
		}

		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (int gap : gapsForAnimation)
		{
			min = Math.min(min, gap);
			max = Math.max(max, gap);
		}

		return min == max ? "  every " + min + "t" : "  every " + min + "-" + max + "t";
	}

	private static int averageTick(List<Ev> group)
	{
		int total = 0;
		for (Ev ev : group)
		{
			total += ev.tick;
		}
		return total / group.size();
	}

	private static int averageCycle(List<Ev> group)
	{
		int total = 0;
		for (Ev ev : group)
		{
			total += ev.cycle;
		}
		return total / group.size();
	}

	private static int averageAmount(List<Ev> group)
	{
		int total = 0;
		for (Ev ev : group)
		{
			total += ev.amount;
		}
		return total / group.size();
	}

	/**
	 * @return whether this actor is part of the fight, so its events are worth logging
	 */
	private boolean isRelevant(Actor actor)
	{
		if (actor == null)
		{
			return false;
		}

		if (Category.ALL_ACTORS.isEnabled())
		{
			return true;
		}

		final Player local = client.getLocalPlayer();
		return actor == local
			|| actor == target
			|| (local != null && (actor.getInteracting() == local || local.getInteracting() == actor));
	}

	private String describe(Actor actor)
	{
		if (actor == null)
		{
			return "?";
		}

		if (actor == client.getLocalPlayer())
		{
			return "You";
		}

		final String name = actor.getName() == null ? "?" : actor.getName();
		if (actor instanceof NPC)
		{
			final NPC npc = (NPC) actor;
			return name + "(id " + npc.getId() + ", lvl " + npc.getCombatLevel() + ")";
		}

		return name;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		soundsThisTick.clear();
		flushPending();
		flushSpotAnims();
		flushGroundGfx();
		resolveTarget();

		// These have no event of their own, so they are sampled once a tick and only logged on a change
		sampleMovement();
		sampleHealth();
		sampleOverhead();

		if (Category.POSE.isEnabled())
		{
			logPose(client.getLocalPlayer());
			logPose(target);
		}

		if (fightStartTick >= 0 && client.getTickCount() - lastEventTick > FIGHT_TIMEOUT_TICKS)
		{
			endFight();
		}
	}

	private void flushSpotAnims()
	{
		if (pendingSpotAnims.isEmpty())
		{
			return;
		}

		if (fightStartTick < 0)
		{
			// Not in a fight, so these were scenery rather than part of an attack
			pendingSpotAnims.clear();
			pendingSpotAnimStamps.clear();
			return;
		}

		for (Map.Entry<Actor, List<Integer>> entry : pendingSpotAnims.entrySet())
		{
			final Actor actor = entry.getKey();
			final List<Integer> added = entry.getValue();
			final int[] stamp = pendingSpotAnimStamps.get(actor);
			final StringBuilder ids = new StringBuilder();
			for (int id : added)
			{
				ids.append(ids.length() == 0 ? "" : ",").append(id);
			}

			addEventAt(stamp[0], stamp[1], describe(actor) + " spotanim +" + ids,
				Kind.SPOTANIM, who(actor), added.get(0), 0);
		}

		pendingSpotAnims.clear();
		pendingSpotAnimStamps.clear();
	}

	private void flushGroundGfx()
	{
		if (pendingGroundGfx.isEmpty())
		{
			return;
		}

		if (fightStartTick < 0)
		{
			pendingGroundGfx.clear();
			pendingGroundGfxStamps.clear();
			return;
		}

		final Player local = client.getLocalPlayer();
		for (Map.Entry<Integer, List<WorldPoint>> entry : pendingGroundGfx.entrySet())
		{
			final List<WorldPoint> tiles = entry.getValue();
			final int[] stamp = pendingGroundGfxStamps.get(entry.getKey());
			final StringBuilder positions = new StringBuilder();
			int nearest = Integer.MAX_VALUE;
			for (WorldPoint tile : tiles)
			{
				positions.append(positions.length() == 0 ? "" : " ")
					.append('(').append(tile.getX()).append(',').append(tile.getY()).append(')');
				if (local != null && local.getWorldLocation() != null)
				{
					nearest = Math.min(nearest, local.getWorldLocation().distanceTo(tile));
				}
			}

			final Integer lead = pendingGroundGfxLeads.get(entry.getKey());
			addEventAt(stamp[0], stamp[1], "ground gfx " + entry.getKey() + " x" + tiles.size()
					+ " plane " + tiles.get(0).getPlane() + " " + positions
					+ (nearest == Integer.MAX_VALUE ? "" : "  nearest " + nearest)
					+ (lead == null || lead == 0 ? "" : "  starts in " + lead + "c"),
				null, null, 0, 0);
		}

		pendingGroundGfx.clear();
		pendingGroundGfxStamps.clear();
		pendingGroundGfxLeads.clear();
	}

	private void logPose(Actor actor)
	{
		if (actor == null)
		{
			return;
		}

		final int pose = actor.getPoseAnimation();
		final Integer last = lastPoses.put(actor, pose);
		if (last != null && last != pose && fightStartTick >= 0)
		{
			addEvent(describe(actor) + " pose " + pose + " (was " + last + ")");
		}
	}


	/**
	 * Positions are logged only when they change, and with the distance to the target, since what
	 * matters when writing a plugin is the step, not the coordinates being republished every tick.
	 */
	private void sampleMovement()
	{
		if (!Category.MOVEMENT.isEnabled() || fightStartTick < 0)
		{
			return;
		}

		final Player local = client.getLocalPlayer();
		if (local != null)
		{
			final WorldPoint position = local.getWorldLocation();
			if (position != null && !position.equals(lastLocalPosition))
			{
				final String step = lastLocalPosition == null ? "" : " (" + lastLocalPosition.distanceTo(position) + " tiles)";
				lastLocalPosition = position;
				addPassive("You moved to " + format(position) + step + distanceToTarget(position));
			}
		}

		if (target != null)
		{
			final WorldPoint position = target.getWorldLocation();
			if (position != null && !position.equals(lastTargetPosition))
			{
				lastTargetPosition = position;
				addPassive(describe(target) + " moved to " + format(position));
			}
		}
	}

	private String distanceToTarget(WorldPoint from)
	{
		if (target == null)
		{
			return "";
		}

		final WorldPoint targetPosition = target.getWorldLocation();
		return targetPosition == null ? "" : "  dist " + from.distanceTo(targetPosition);
	}


	/**
	 * Orientation in JAU (0-2047, 0 = south, increasing anticlockwise) as a compass point, since the
	 * raw number is hard to sanity check while reading a log.
	 */
	private static String compass(int jau)
	{
		final String[] points = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		return points[((jau + 128) / 256) & 7];
	}

	private String positionOf(Actor actor)
	{
		final WorldPoint position = actor.getWorldLocation();
		return position == null ? "" : "  at " + format(position);
	}

	private static String format(WorldPoint position)
	{
		return "(" + position.getX() + "," + position.getY() + "," + position.getPlane()
			+ " region " + position.getRegionID() + ")";
	}

	private void sampleHealth()
	{
		if (!Category.HEALTH.isEnabled() || fightStartTick < 0 || target == null)
		{
			return;
		}

		final int ratio = target.getHealthRatio();
		final int scale = target.getHealthScale();
		if (ratio < 0 || scale <= 0)
		{
			return;
		}

		final int percent = ratio * 100 / scale;
		if (percent != lastTargetHealth)
		{
			lastTargetHealth = percent;
			addPassive(describe(target) + " hp " + percent + "% (" + ratio + "/" + scale + ")");
		}
	}

	private void sampleOverhead()
	{
		if (!Category.OVERHEAD.isEnabled() || fightStartTick < 0 || target == null)
		{
			return;
		}

		final HeadIcon overhead = target instanceof NPC ? ((NPC) target).getOverheadIcon()
			: (target instanceof Player ? ((Player) target).getOverheadIcon() : null);

		if (overhead != lastTargetOverhead)
		{
			lastTargetOverhead = overhead;
			addPassive(describe(target) + " overhead " + (overhead == null ? "none" : overhead.name()));
		}
	}

	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		if (!Category.TRANSFORM.isEnabled() || !isRelevant(event.getNpc()))
		{
			return;
		}

		// Bosses usually swap npc id between phases, which is the cleanest thing for a plugin to key off
		addPassive(event.getNpc().getName() + " transformed "
			+ (event.getOld() == null ? "?" : String.valueOf(event.getOld().getId()))
			+ " -> " + event.getNpc().getId());
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated event)
	{
		if (!Category.GROUND_GFX.isEnabled() || fightStartTick < 0)
		{
			return;
		}

		final GraphicsObject graphicsObject = event.getGraphicsObject();
		if (graphicsObject.getId() == NULL_GRAPHIC && !Category.IDLE.isEnabled())
		{
			return;
		}

		final WorldPoint position = WorldPoint.fromLocal(client, graphicsObject.getLocation());
		final Player local = client.getLocalPlayer();
		if (local == null || local.getWorldLocation().distanceTo(position) > NEARBY_TILES)
		{
			return;
		}

		// One event is posted per tile, so a wide attack produces a burst. They are collected here and
		// written as a single line per graphic on the next tick, which is also the shape a plugin wants:
		// the set of tiles the attack covers.
		pendingGroundGfxLeads.putIfAbsent(graphicsObject.getId(),
			(graphicsObject.getStartCycle() - client.getGameCycle()));
		pendingGroundGfx.computeIfAbsent(graphicsObject.getId(), k -> new ArrayList<>()).add(position);
		pendingGroundGfxStamps.computeIfAbsent(graphicsObject.getId(),
			k -> new int[]{client.getTickCount(), client.getGameCycle()});
	}


	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		logSceneObject(event.getGameObject(), "game object", "spawned");
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		logSceneObject(event.getGameObject(), "game object", "despawned");
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
		logSceneObject(event.getGroundObject(), "ground object", "spawned");
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
		logSceneObject(event.getDecorativeObject(), "decoration", "spawned");
	}

	/**
	 * Scene objects appear here because a mechanic that is drawn on the floor is not necessarily a
	 * {@link GraphicsObject} - it can equally be an object the server adds to the scene.
	 */
	private void logSceneObject(TileObject object, String what, String action)
	{
		if (!Category.OBJECTS.isEnabled() || fightStartTick < 0 || object == null)
		{
			return;
		}

		final Player local = client.getLocalPlayer();
		final WorldPoint position = object.getWorldLocation();
		if (local == null || position == null || local.getWorldLocation().distanceTo(position) > NEARBY_TILES)
		{
			return;
		}

		addPassive(what + " " + object.getId() + " " + action + " at " + format(position)
			+ "  dist " + local.getWorldLocation().distanceTo(position));
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!Category.VARBIT.isEnabled() || fightStartTick < 0 || event.getVarbitId() == -1)
		{
			return;
		}

		final int id = event.getVarbitId();
		if (mutedVarbits.contains(id))
		{
			return;
		}

		final Integer previous = lastVarbits.put(id, event.getValue());
		if (previous != null && previous == event.getValue())
		{
			return;
		}

		// A varbit that changes on several consecutive ticks is a clock or a timer, not a phase, and
		// would otherwise bury everything else. Mute it once, with a line saying so.
		final int tick = client.getTickCount();
		final Integer lastTick = varbitChangeTicks.put(id, tick);
		final int run = lastTick != null && tick - lastTick <= 1 ? varbitRunLengths.getOrDefault(id, 1) + 1 : 1;
		varbitRunLengths.put(id, run);

		final String name = VARBIT_NAMES.getOrDefault(id, String.valueOf(id));
		if (run >= CHATTY_VARBIT_TICKS)
		{
			mutedVarbits.add(id);
			addPassive("varbit " + name + " muted (changes every tick)");
			return;
		}

		addPassive("varbit " + name + " = " + event.getValue()
			+ (previous == null ? "" : " (was " + previous + ")"));
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!Category.CHAT.isEnabled() || fightStartTick < 0)
		{
			return;
		}

		addPassive("chat [" + event.getType() + "] " + event.getMessage());
	}

	@Subscribe
	public void onSoundEffectPlayed(SoundEffectPlayed event)
	{
		if (!Category.SOUND.isEnabled() || fightStartTick < 0)
		{
			return;
		}

		if (soundsThisTick.add("s" + event.getSoundId()))
		{
			addPassive("sound " + event.getSoundId());
		}
	}

	@Subscribe
	public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed event)
	{
		if (!Category.SOUND.isEnabled() || fightStartTick < 0)
		{
			return;
		}

		final String key = "a" + event.getSoundId() + ":" + System.identityHashCode(event.getSource());
		if (soundsThisTick.add(key))
		{
			addPassive("area sound " + event.getSoundId()
				+ (event.getSource() == null ? "" : " from " + describe(event.getSource())));
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		logNearbyNpc(event.getNpc(), "spawned");
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		logNearbyNpc(event.getNpc(), "despawned");
	}

	private void logNearbyNpc(NPC npc, String what)
	{
		if (!Category.ADDS.isEnabled() || fightStartTick < 0)
		{
			return;
		}

		final Player local = client.getLocalPlayer();
		if (local == null || local.getWorldLocation().distanceTo(npc.getWorldLocation()) > NEARBY_TILES)
		{
			return;
		}

		addPassive(npc.getName() + "(id " + npc.getId() + ") " + what + " at " + format(npc.getWorldLocation()));
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		final Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		// The local player picking a target is what opens a fight, so this is checked before isRelevant
		if (event.getSource() == local && event.getTarget() != null)
		{
			target = event.getTarget();
			nameTarget();
		}
		else if (event.getTarget() == local && target == null)
		{
			target = event.getSource();
			nameTarget();
		}
		else if (!isRelevant(event.getSource()))
		{
			return;
		}

		if (!Category.INTERACTING.isEnabled())
		{
			return;
		}

		// The client re-posts the pair every tick for as long as the interaction lasts, and posts a null
		// target when it ends, neither of which is worth a line
		if (event.getTarget() == null
			|| (event.getSource() == lastInteractSource && event.getTarget() == lastInteractTarget))
		{
			return;
		}

		lastInteractSource = event.getSource();
		lastInteractTarget = event.getTarget();

		addEvent(describe(event.getSource()) + " targets " + describe(event.getTarget()));
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (!Category.ANIMATION.isEnabled() || !isRelevant(event.getActor()))
		{
			return;
		}

		final Actor actor = event.getActor();
		final int animation = actor.getAnimation();
		if (animation == -1 && !Category.IDLE.isEnabled())
		{
			return;
		}

		// The client re-posts the same animation on repeat attacks; only transitions are interesting
		final Integer last = lastAnimations.put(actor, animation);
		if (last != null && last == animation)
		{
			return;
		}

		// A new animation on the thing we are fighting is what a step is
		if (actor == target && animation != -1)
		{
			step++;
		}

		final Integer startedAt = animationStartTicks.put(actor, client.getTickCount());
		final String held = startedAt == null ? "" : " after " + (client.getTickCount() - startedAt) + "t";

		// Orientation is what turns a movement into a direction: the tiles a mechanic asks for are
		// relative to where the NPC is facing, not to world north
		final String facing = actor == target
			? "  facing " + actor.getCurrentOrientation() + " (" + compass(actor.getCurrentOrientation()) + ")"
			: "";

		addEvent(describe(actor) + " anim " + animation
			+ (last == null ? "" : " (was " + last + held + ")")
			+ facing + positionOf(actor), Kind.ANIM, who(actor), animation, 0);
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		if (!Category.SPOTANIM.isEnabled() || !isRelevant(event.getActor()))
		{
			return;
		}

		final Actor actor = event.getActor();
		final Set<Integer> current = new HashSet<>();
		for (ActorSpotAnim spotAnim : actor.getSpotAnims())
		{
			current.add(spotAnim.getId());
		}

		final Set<Integer> previous = lastSpotAnims.put(actor, current);
		if (current.isEmpty())
		{
			if (Category.IDLE.isEnabled() && previous != null && !previous.isEmpty())
			{
				addEvent(describe(actor) + " spotanim cleared");
			}

			return;
		}

		// One event is posted per spotanim added, so only what is new since the last event is logged,
		// and the tick's additions are gathered into a single line by the flush on the next tick
		final List<Integer> added = new ArrayList<>();
		for (int id : current)
		{
			if (previous == null || !previous.contains(id))
			{
				added.add(id);
			}
		}

		if (added.isEmpty())
		{
			return;
		}

		pendingSpotAnims.computeIfAbsent(actor, k -> new ArrayList<>()).addAll(added);
		pendingSpotAnimStamps.computeIfAbsent(actor,
			k -> new int[]{client.getTickCount(), client.getGameCycle()});
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		final Projectile projectile = event.getProjectile();
		if (!Category.PROJECTILE.isEnabled() || !seenProjectiles.add(projectile))
		{
			return;
		}

		if (!isRelevant(projectile.getSourceActor()) && !isRelevant(projectile.getTargetActor()))
		{
			return;
		}

		addEvent("projectile " + projectile.getId()
				+ " " + describe(projectile.getSourceActor())
				+ " -> " + describe(projectile.getTargetActor())
				+ " (flight " + ((projectile.getEndCycle() - projectile.getStartCycle()) / 30) + "t)",
			Kind.PROJECTILE, who(projectile.getTargetActor()), projectile.getId(), 0);
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!isRelevant(event.getActor()))
		{
			return;
		}

		// A fight can also start with us being attacked, in which case nothing has set a target yet
		if (target == null)
		{
			final Player local = client.getLocalPlayer();
			target = event.getActor() == local ? (local == null ? null : local.getInteracting()) : event.getActor();
			nameTarget();
		}

		final int amount = event.getHitsplat().getAmount();
		if (event.getHitsplat().isMine())
		{
			damageDealt += amount;
		}
		else if (event.getActor() == client.getLocalPlayer())
		{
			damageTaken += amount;
		}

		if (!Category.HITSPLAT.isEnabled())
		{
			return;
		}

		final int type = event.getHitsplat().getHitsplatType();
		addEvent("hit " + describe(event.getActor()) + " " + amount
				+ " (" + HITSPLAT_NAMES.getOrDefault(type, "type " + type) + ")",
			Kind.HIT, who(event.getActor()), type, amount);
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (!isRelevant(event.getActor()))
		{
			return;
		}

		addEvent(describe(event.getActor()) + " died");

		if (event.getActor() == target || event.getActor() == client.getLocalPlayer())
		{
			endFight();
		}
	}

	@Override
	public void open()
	{
		eventBus.register(this);
		super.open();
	}

	@Override
	public void close()
	{
		super.close();
		eventBus.unregister(this);
		endFight();
		lastAnimations.clear();
		lastPoses.clear();
		seenProjectiles.clear();
	}
}
