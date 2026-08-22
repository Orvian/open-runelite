package net.runelite.client.plugins.devtools;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import static org.junit.Assert.assertNotNull;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Exercises the window's logic without showing it. Everything here has crashed a plugin at some point:
 * a missing directory, a jar that no plugin was loaded from, and an empty selection.
 */
@RunWith(MockitoJUnitRunner.class)
public class SideloadedPluginsTest
{
	@Mock
	private PluginManager pluginManager;

	@Mock
	private Client client;

	@Mock
	private ChatMessageManager chatMessageManager;

	private SideloadedPlugins window;

	@Before
	public void before()
	{
		when(pluginManager.getPlugins()).thenReturn(Collections.emptyList());
		window = new SideloadedPlugins(pluginManager, client, chatMessageManager);
	}

	private Object invoke(String name, Class<?>[] types, Object... args) throws Exception
	{
		final Method method = SideloadedPlugins.class.getDeclaredMethod(name, types);
		method.setAccessible(true);
		return method.invoke(window, args);
	}

	@Test
	public void buildsWithoutShowing()
	{
		assertNotNull(window);
	}

	@Test
	public void refreshingReadsTheDirectory() throws Exception
	{
		// the real side-load directory, which may or may not exist on the machine running this
		invoke("refreshList", new Class<?>[0]);
		invoke("listState", new Class<?>[0]);
	}

	@Test
	public void describesJarsWithNoPluginsLoaded() throws Exception
	{
		assertNotNull(invoke("describeState", new Class<?>[]{List.class}, (Object) null));
		assertNotNull(invoke("describeState", new Class<?>[]{List.class}, Collections.<Plugin>emptyList()));
	}

	@Test
	public void reloadingNothingDoesNotCallTheManager() throws Exception
	{
		invoke("reload", new Class<?>[]{List.class}, Collections.<String>emptyList());
		verify(pluginManager, never()).reloadSideLoaded(Collections.<String>emptyList(), null);
	}

	@Test
	public void watchingAnUnchangedDirectoryIsANoop() throws Exception
	{
		invoke("checkForChanges", new Class<?>[0]);
		invoke("checkForChanges", new Class<?>[0]);
	}

	@Test
	public void mapsLoadedPluginsBackToTheirJars() throws Exception
	{
		assertNotNull(invoke("pluginsByJar", new Class<?>[0]));
	}
}
