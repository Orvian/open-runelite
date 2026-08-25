/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
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
package net.runelite.client.plugins;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.ReflectUtil;

@Slf4j
class PluginClassLoader extends URLClassLoader implements ReflectUtil.PrivateLookupableClassLoader
{
	private final ClassLoader parent;

	@Getter
	@Setter
	private MethodHandles.Lookup lookup;

	PluginClassLoader(File plugin, ClassLoader parent) throws MalformedURLException
	{
		// null parent classloader, or else class path scanning includes everything from the main class loader
		super(new URL[]{plugin.toURI().toURL()}, null);

		this.parent = parent;

		try
		{
			// without this the EventBus can't create lambdas for @Subscribe methods in side-loaded
			// plugins, and silently falls back to reflective invocation
			ReflectUtil.installLookupHelper(this);
		}
		catch (RuntimeException ex)
		{
			// not fatal: the EventBus falls back to reflection, so loading the plugin is still worth trying
			log.warn("unable to install lookup helper for {}, @Subscribe methods will use reflection",
				plugin.getName(), ex);
		}
	}

	@Override
	public Class<?> defineClass0(String name, byte[] b, int off, int len) throws ClassFormatError
	{
		return super.defineClass(name, b, off, len);
	}

	@Override
	public Class<?> loadClass(String name) throws ClassNotFoundException
	{
		try
		{
			return super.loadClass(name);
		}
		catch (ClassNotFoundException ex)
		{
			// fall back to main class loader
			return parent.loadClass(name);
		}
	}
}