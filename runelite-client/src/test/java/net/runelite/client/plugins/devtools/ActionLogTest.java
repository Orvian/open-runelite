package net.runelite.client.plugins.devtools;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Deque;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.EventBus;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import static org.mockito.Mockito.lenient;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Drives the capture pipeline the way a fight does: an animation opens a fight, the tick flushes what
 * was buffered into the view. This is the path that was rewritten from a label per line to a buffered
 * text area, so it is the one worth executing.
 */
@RunWith(MockitoJUnitRunner.class)
public class ActionLogTest
{
	@Mock
	private Client client;

	@Mock
	private EventBus eventBus;

	@Mock
	private Player localPlayer;

	@Mock
	private NPC npc;

	private ActionLog log;

	@Before
	public void before()
	{
		lenient().when(client.getTickCount()).thenReturn(100);
		lenient().when(client.getGameCycle()).thenReturn(3000);
		lenient().when(client.getLocalPlayer()).thenReturn(localPlayer);
		lenient().when(localPlayer.getName()).thenReturn("You");
		lenient().when(localPlayer.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
		lenient().when(npc.getName()).thenReturn("Lowerniel Drakan");
		lenient().when(npc.getId()).thenReturn(16204);
		lenient().when(npc.getCombatLevel()).thenReturn(1063);
		lenient().when(npc.getAnimation()).thenReturn(14317);
		lenient().when(npc.getCurrentOrientation()).thenReturn(1024);
		lenient().when(npc.getWorldLocation()).thenReturn(new WorldPoint(3205, 3205, 0));

		log = new ActionLog(client, eventBus);
	}

	@SuppressWarnings("unchecked")
	private Deque<String> lines() throws Exception
	{
		final Field field = ActionLog.class.getDeclaredField("lines");
		field.setAccessible(true);
		return (Deque<String>) field.get(log);
	}

	private void invoke(String name) throws Exception
	{
		final Method method = ActionLog.class.getDeclaredMethod(name);
		method.setAccessible(true);
		method.invoke(log);
	}

	/**
	 * The buffered flush hands work to the event dispatch thread, so the test has to wait for it.
	 */
	private void pumpSwing() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
		});
		SwingUtilities.invokeAndWait(() ->
		{
		});
	}

	@Test
	public void anAnimationOpensAFightAndReachesTheView() throws Exception
	{
		final AnimationChanged animation = new AnimationChanged();
		animation.setActor(localPlayer);
		log.onAnimationChanged(animation);

		log.onGameTick(new GameTick());
		pumpSwing();

		assertFalse("the animation should have been captured", lines().isEmpty());
	}

	@Test
	public void tickingWithNothingHappeningIsSafe() throws Exception
	{
		log.onGameTick(new GameTick());
		log.onGameTick(new GameTick());
		pumpSwing();
	}

	@Test
	public void summarisingAnEmptyFightIsSafe() throws Exception
	{
		invoke("summarise");
		pumpSwing();
	}

	@Test
	public void filteringRebuildsTheView() throws Exception
	{
		final AnimationChanged animation = new AnimationChanged();
		animation.setActor(localPlayer);
		log.onAnimationChanged(animation);
		log.onGameTick(new GameTick());
		pumpSwing();

		invoke("rebuild");
		pumpSwing();
		assertNotNull(lines());
	}

	@Test
	public void closingAfterAFightIsSafe() throws Exception
	{
		final AnimationChanged animation = new AnimationChanged();
		animation.setActor(localPlayer);
		log.onAnimationChanged(animation);
		log.onGameTick(new GameTick());
		pumpSwing();

		log.close();
		pumpSwing();
	}
}
