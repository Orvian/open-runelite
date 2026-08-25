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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
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

	private javax.swing.JLabel status() throws Exception
	{
		final Field field = ActionLog.class.getDeclaredField("statusBar");
		field.setAccessible(true);
		return (javax.swing.JLabel) field.get(log);
	}

	private javax.swing.JButton button() throws Exception
	{
		final Field field = ActionLog.class.getDeclaredField("startStopBtn");
		field.setAccessible(true);
		return (javax.swing.JButton) field.get(log);
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
	public void capturedLinesReachTheVisibleArea() throws Exception
	{
		final AnimationChanged animation = new AnimationChanged();
		animation.setActor(localPlayer);
		log.onAnimationChanged(animation);

		log.onGameTick(new GameTick());
		pumpSwing();

		final Field field = ActionLog.class.getDeclaredField("area");
		field.setAccessible(true);
		final javax.swing.JTextArea area = (javax.swing.JTextArea) field.get(log);
		assertFalse("nothing was written to the text area: " + lines(), area.getText().isEmpty());
	}

	@Test
	public void tickingWithNothingHappeningIsSafe() throws Exception
	{
		log.onGameTick(new GameTick());
		log.onGameTick(new GameTick());
		pumpSwing();
	}

	@Test
	public void pausingAndResumingKeepsTheButtonAndStatusInStep() throws Exception
	{
		final Method setRecording = ActionLog.class.getDeclaredMethod("setRecording", boolean.class);
		setRecording.setAccessible(true);

		setRecording.invoke(log, false);
		pumpSwing();
		assertEquals("Start logging", button().getText());
		assertTrue(status().getText().contains("paused"));

		setRecording.invoke(log, true);
		pumpSwing();
		assertEquals("Stop logging", button().getText());
		assertTrue(status().getText().contains("recording"));
	}

	@Test
	public void pausedCaptureDropsEvents() throws Exception
	{
		final Method setRecording = ActionLog.class.getDeclaredMethod("setRecording", boolean.class);
		setRecording.setAccessible(true);
		setRecording.invoke(log, false);
		pumpSwing();

		final int before = lines().size();
		final AnimationChanged animation = new AnimationChanged();
		animation.setActor(localPlayer);
		log.onAnimationChanged(animation);
		log.onGameTick(new GameTick());
		pumpSwing();

		// the only new line may be the "logging stopped" marker, never the animation
		assertEquals("events should not be captured while paused", before, lines().size());
	}

	@Test
	public void statusReportsTheFightOnceOneStarts() throws Exception
	{
		final AnimationChanged animation = new AnimationChanged();
		animation.setActor(localPlayer);
		log.onAnimationChanged(animation);
		log.onGameTick(new GameTick());
		pumpSwing();

		assertTrue(status().getText(), status().getText().contains("lines"));
	}

	@Test
	public void copiedOutputExplainsItsOwnFormat() throws Exception
	{
		final Method legend = ActionLog.class.getDeclaredMethod("legend");
		legend.setAccessible(true);
		final String header = (String) legend.invoke(log);

		assertTrue(header, header.contains("format:"));
		assertTrue(header, header.contains("capturing:"));
		assertTrue(header, header.contains("lines captured"));
	}

	@Test
	public void clicksAreLoggedWithoutAFight() throws Exception
	{
		final net.runelite.api.MenuEntry entry = org.mockito.Mockito.mock(net.runelite.api.MenuEntry.class);
		org.mockito.Mockito.when(entry.getOption()).thenReturn("Chop down");
		org.mockito.Mockito.when(entry.getTarget()).thenReturn("<col=00ff00>Tree");
		org.mockito.Mockito.when(entry.getType()).thenReturn(net.runelite.api.MenuAction.GAME_OBJECT_FIRST_OPTION);
		org.mockito.Mockito.when(entry.getIdentifier()).thenReturn(1276);
		org.mockito.Mockito.when(entry.getParam0()).thenReturn(50);
		org.mockito.Mockito.when(entry.getParam1()).thenReturn(51);
		org.mockito.Mockito.when(entry.getItemId()).thenReturn(-1);

		log.onMenuOptionClicked(new net.runelite.api.events.MenuOptionClicked(entry));
		log.onGameTick(new GameTick());
		pumpSwing();

		final String all = String.join("\n", lines());
		assertTrue(all, all.contains("Chop down"));
		assertTrue("tags should be stripped: " + all, all.contains("on \"Tree\""));
	}

	@Test
	public void everyFilterExplainsItself() throws Exception
	{
		final Class<?> category = Class.forName(
			"net.runelite.client.plugins.devtools.ActionLog$Category");
		final java.lang.reflect.Method getCheckBox = category.getDeclaredMethod("getCheckBox");
		getCheckBox.setAccessible(true);

		for (Object c : category.getEnumConstants())
		{
			final javax.swing.JCheckBox box = (javax.swing.JCheckBox) getCheckBox.invoke(c);
			assertNotNull("no tooltip on filter " + c, box.getToolTipText());
		}
	}

	/**
	 * The status bar is built on the event dispatch thread. Anything it reads from the game's api throws
	 * "must be called on client thread", so it must read nothing.
	 */
	@Test
	public void statusBarNeverTouchesTheGameApi() throws Exception
	{
		final AnimationChanged animation = new AnimationChanged();
		animation.setActor(npc);
		log.onAnimationChanged(animation);
		log.onGameTick(new GameTick());
		pumpSwing();

		org.mockito.Mockito.clearInvocations(npc, client, localPlayer);
		invoke("updateStatus");
		pumpSwing();

		org.mockito.Mockito.verifyNoInteractions(npc);
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
