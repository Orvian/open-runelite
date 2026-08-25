package net.runelite.client.plugins;

import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.ReflectUtil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PluginClassLoaderTest
{
	public static class Probe
	{
		public static int hits;

		@Subscribe
		public void onGameTick(GameTick t)
		{
			hits++;
		}
	}

	@Test
	public void lambdaCreationWorksForSideloadedClasses() throws Throwable
	{
		String name = Probe.class.getName();
		String path = name.replace('.', '/') + ".class";
		File jar = File.createTempFile("sideload-probe", ".jar");
		jar.deleteOnExit();
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()));
			InputStream in = getClass().getClassLoader().getResourceAsStream(path))
		{
			out.putNextEntry(new JarEntry(path));
			copy(in, out);
			out.closeEntry();
		}

		try (PluginClassLoader cl = new PluginClassLoader(jar, getClass().getClassLoader()))
		{
			Class<?> clazz = cl.loadClass(name);
			assertEquals(cl, clazz.getClassLoader());

			// EventBus.register does exactly this; without a PRIVATE|MODULE lookup LambdaMetafactory
			// rejects the call with "Invalid caller" and the bus silently falls back to reflection
			MethodHandles.Lookup caller = ReflectUtil.privateLookupIn(clazz);
			assertTrue("lookup lacks PRIVATE", (caller.lookupModes() & MethodHandles.Lookup.PRIVATE) != 0);
			assertTrue("lookup lacks MODULE", (caller.lookupModes() & MethodHandles.Lookup.MODULE) != 0);

			MethodType subscription = MethodType.methodType(void.class, GameTick.class);
			MethodHandle target = caller.findVirtual(clazz, "onGameTick", subscription);
			CallSite site = LambdaMetafactory.metafactory(
				caller,
				"accept",
				MethodType.methodType(Consumer.class, clazz),
				subscription.changeParameterType(0, Object.class),
				target,
				subscription);

			Object instance = clazz.getDeclaredConstructor().newInstance();
			@SuppressWarnings("unchecked")
			Consumer<Object> lambda = (Consumer<Object>) site.getTarget().bindTo(instance).invokeExact();
			assertNotNull(lambda);
			lambda.accept(new GameTick());

			java.lang.reflect.Field hits = clazz.getDeclaredField("hits");
			hits.setAccessible(true);
			assertEquals(1, hits.getInt(null));
		}
	}

	private static void copy(InputStream in, OutputStream out) throws Exception
	{
		ByteStreams.copy(in, out);
	}
}
