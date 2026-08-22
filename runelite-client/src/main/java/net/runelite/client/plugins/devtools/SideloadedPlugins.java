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
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.time.Instant;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.RuneLite;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.api.ChatMessageType;
import net.runelite.client.ui.DynamicGridLayout;

/**
 * Lists the jars in the side-loaded plugins directory and reloads them, replacing the two step
 * "type the file names into the config, then press a button on a panel" flow.
 * <p>
 * Reloading is the whole point of the window, so it optionally watches the directory and reloads a jar
 * as soon as it is rebuilt, which is what makes editing a plugin a save-and-see loop.
 */
@Slf4j
class SideloadedPlugins extends DevToolsFrame
{
	private static final File SIDELOADED_PLUGINS = new File(RuneLite.RUNELITE_DIR, "sideloaded-plugins");

	/**
	 * How often the directory is checked for rebuilt jars while auto reload is on.
	 */
	private static final int WATCH_INTERVAL_MS = 1000;

	private final PluginManager pluginManager;
	private final Client client;
	private final ChatMessageManager chatMessageManager;

	private final JPanel jarList = new JPanel();
	private final JLabel status = new JLabel("Ready");
	private final JCheckBox autoReload = new JCheckBox("Auto reload when a jar changes", false);
	private final JCheckBox autoEnable = new JCheckBox("Enable after reload", true);
	private final JCheckBox newestFirst = new JCheckBox("Newest first", false);
	private final JTextField search = new JTextField();

	private final Map<String, JCheckBox> selection = new LinkedHashMap<>();
	private final Map<String, Long> lastModified = new HashMap<>();

	/**
	 * Jars seen changing, with the stamp they had when noticed. A build writes a jar over several
	 * polls, so a reload only happens once the stamp stops moving.
	 */
	private final Map<String, String> settling = new HashMap<>();

	/**
	 * What the list was showing when it was last drawn. Rebuilding it every poll would recreate every
	 * checkbox once a second, which loses clicks, so it is only redrawn when this changes.
	 */
	private String shownState = "";

	private final Timer watcher = new Timer(WATCH_INTERVAL_MS, e ->
	{
		checkForChanges();
		if (!listState().equals(shownState))
		{
			refreshList();
		}
	});

	@Inject
	SideloadedPlugins(PluginManager pluginManager, Client client, ChatMessageManager chatMessageManager)
	{
		this.pluginManager = pluginManager;
		this.client = client;
		this.chatMessageManager = chatMessageManager;

		setTitle("RuneLite Side-loaded Plugins");
		setLayout(new BorderLayout());

		jarList.setLayout(new DynamicGridLayout(0, 1, 0, 2));

		final JPanel listWrapper = new JPanel();
		listWrapper.setLayout(new BorderLayout());
		listWrapper.add(jarList, BorderLayout.NORTH);

		final JScrollPane scroller = new JScrollPane(listWrapper);
		scroller.setPreferredSize(new Dimension(520, 420));
		add(scroller, BorderLayout.CENTER);

		final JPanel searchRow = new JPanel();
		searchRow.setLayout(new BorderLayout());
		searchRow.add(new JLabel(" Search: "), BorderLayout.WEST);
		searchRow.add(search, BorderLayout.CENTER);
		search.setToolTipText("Show only jars whose name contains this");
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				refreshList();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				refreshList();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				refreshList();
			}
		});
		add(searchRow, BorderLayout.NORTH);

		final JPanel options = new JPanel();
		options.setLayout(new GridLayout(0, 3, 2, 2));
		options.add(autoReload);
		options.add(autoEnable);
		options.add(newestFirst);
		newestFirst.addActionListener(e -> refreshList());

		final JPanel buttons = new JPanel();
		buttons.setLayout(new FlowLayout());

		final JButton reloadSelected = new JButton("Reload selected");
		reloadSelected.addActionListener(e -> reload(selected()));
		buttons.add(reloadSelected);

		final JButton reloadAll = new JButton("Reload all");
		reloadAll.addActionListener(e -> reload(null));
		buttons.add(reloadAll);

		final JButton selectAll = new JButton("All");
		selectAll.addActionListener(e -> setAllSelected(true));
		buttons.add(selectAll);

		final JButton selectNone = new JButton("None");
		selectNone.addActionListener(e -> setAllSelected(false));
		buttons.add(selectNone);

		final JButton refresh = new JButton("Refresh");
		refresh.addActionListener(e -> refreshList());
		buttons.add(refresh);

		final JButton openFolder = new JButton("Open folder");
		openFolder.addActionListener(e -> openFolder());
		buttons.add(openFolder);

		final JPanel south = new JPanel();
		south.setLayout(new BorderLayout());
		south.add(status, BorderLayout.NORTH);
		south.add(options, BorderLayout.CENTER);
		south.add(buttons, BorderLayout.SOUTH);
		add(south, BorderLayout.SOUTH);

		pack();
	}

	/**
	 * A cheap signature of everything the list displays: which jars exist, when they were built, and
	 * which plugins from them are loaded and enabled.
	 */
	private String listState()
	{
		final File[] files = SIDELOADED_PLUGINS.listFiles((dir, name) -> name.endsWith(".jar"));
		if (files == null)
		{
			return "";
		}

		final Map<String, List<Plugin>> byJar = pluginsByJar();
		final StringBuilder sb = new StringBuilder();
		Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
		for (File file : files)
		{
			sb.append(file.getName()).append(':').append(file.lastModified()).append(':');
			for (Plugin plugin : byJar.getOrDefault(file.getName(), Collections.emptyList()))
			{
				sb.append(plugin.getName()).append(pluginManager.isPluginActive(plugin) ? "+" : "-");
			}
			sb.append(';');
		}

		return sb.toString();
	}

	private List<String> selected()
	{
		final List<String> names = new ArrayList<>();
		for (Map.Entry<String, JCheckBox> entry : selection.entrySet())
		{
			if (entry.getValue().isSelected())
			{
				names.add(entry.getKey());
			}
		}

		return names;
	}

	private void setAllSelected(boolean value)
	{
		selection.values().forEach(cb -> cb.setSelected(value));
	}

	private void refreshList()
	{
		final File[] files = SIDELOADED_PLUGINS.listFiles((dir, name) -> name.endsWith(".jar"));
		final List<String> previouslySelected = selected();

		selection.clear();
		jarList.removeAll();

		if (files == null || files.length == 0)
		{
			jarList.add(new JLabel("No jars in " + SIDELOADED_PLUGINS));
			setStatus("Nothing to load");
			jarList.revalidate();
			jarList.repaint();
			return;
		}

		Arrays.sort(files, newestFirst.isSelected()
			? (a, b) -> Long.compare(b.lastModified(), a.lastModified())
			: (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

		final Map<String, List<Plugin>> byJar = pluginsByJar();
		final String needle = search.getText().toLowerCase();
		int shown = 0;
		int loaded = 0;

		for (File file : files)
		{
			final String name = file.getName();
			lastModified.put(name, file.lastModified());

			final List<Plugin> plugins = byJar.get(name);
			if (plugins != null && !plugins.isEmpty())
			{
				loaded++;
			}

			if (!needle.isEmpty() && !name.toLowerCase().contains(needle))
			{
				continue;
			}

			final JCheckBox checkBox = new JCheckBox("<html>" + describe(file)
				+ "<br><i>" + describeState(plugins) + "</i></html>",
				previouslySelected.contains(name));
			checkBox.setToolTipText(file.getAbsolutePath()
				+ (plugins == null ? "" : "  -  double click to reload just this jar"));
			checkBox.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (e.getClickCount() == 2)
					{
						reload(Collections.singletonList(name));
					}
				}
			});

			selection.put(name, checkBox);
			jarList.add(checkBox);
			shown++;
		}

		// A deleted jar does not unload its plugin, so it would otherwise keep running with no row
		// in the list and no way to see it
		int orphaned = 0;
		for (Map.Entry<String, List<Plugin>> entry : byJar.entrySet())
		{
			if (!new File(SIDELOADED_PLUGINS, entry.getKey()).exists())
			{
				final JLabel label = new JLabel("<html><font color='#c85a5a'>" + entry.getKey()
					+ " (jar deleted, still loaded)</font><br><i>" + describeState(entry.getValue())
					+ "</i></html>");
				label.setToolTipText("Reload all to unload it");
				jarList.add(label);
				orphaned++;
			}
		}

		shownState = listState();
		setStatus(shown + (shown == files.length ? "" : "/" + files.length) + " jars, " + loaded + " loaded"
			+ (orphaned == 0 ? "" : ", " + orphaned + " orphaned"));
		jarList.revalidate();
		jarList.repaint();
	}


	/**
	 * Maps each side-load jar to the plugins currently loaded from it. The jar is recovered from the
	 * class loader's URL, which is the only link back to the file once a plugin is loaded.
	 */
	private Map<String, List<Plugin>> pluginsByJar()
	{
		final Map<String, List<Plugin>> byJar = new HashMap<>();
		for (Plugin plugin : pluginManager.getPlugins())
		{
			final ClassLoader loader = plugin.getClass().getClassLoader();
			if (!(loader instanceof URLClassLoader))
			{
				continue;
			}

			final URL[] urls = ((URLClassLoader) loader).getURLs();
			if (urls.length == 0)
			{
				continue;
			}

			final String path = urls[0].getPath();
			if (!path.contains(SIDELOADED_PLUGINS.getName() + "/"))
			{
				continue;
			}

			final String jar = path.substring(path.lastIndexOf('/') + 1);
			byJar.computeIfAbsent(jar, k -> new ArrayList<>()).add(plugin);
		}

		return byJar;
	}

	/**
	 * @return the jar's state as html, so the list says at a glance what is loaded, what is enabled and
	 * what is sitting in the directory doing nothing
	 */
	private String describeState(List<Plugin> plugins)
	{
		if (plugins == null || plugins.isEmpty())
		{
			return "<font color='#808080'>not loaded</font>";
		}

		final StringBuilder sb = new StringBuilder();
		for (Plugin plugin : plugins)
		{
			final boolean active = pluginManager.isPluginActive(plugin);
			sb.append(sb.length() == 0 ? "" : ", ")
				.append(active ? "<font color='#5ac85a'>" : "<font color='#c8a45a'>")
				.append(plugin.getName())
				.append(active ? " enabled" : " disabled")
				.append("</font>");
		}

		return sb.toString();
	}

	private static String describe(File file)
	{
		final long ageSeconds = Duration.between(Instant.ofEpochMilli(file.lastModified()), Instant.now()).getSeconds();
		final String age = ageSeconds < 60 ? ageSeconds + "s ago"
			: ageSeconds < 3600 ? (ageSeconds / 60) + "m ago"
			: ageSeconds < 86400 ? (ageSeconds / 3600) + "h ago"
			: (ageSeconds / 86400) + "d ago";

		return file.getName() + "  (" + (file.length() / 1024) + " KB, built " + age + ")";
	}

	/**
	 * @param names jars to reload, or null for all of them
	 */
	private void reload(List<String> names)
	{
		if (names != null && names.isEmpty())
		{
			setStatus("Nothing selected");
			return;
		}

		setStatus(names == null ? "Reloading all..." : "Reloading " + names.size() + "...");

		// What is side-loaded right now, so the plugins that the reload brings in can be told apart
		// from the ones that were already there
		final Set<Plugin> before = sideLoaded();

		pluginManager.reloadSideLoaded(names, () -> SwingUtilities.invokeLater(() ->
		{
			final int enabled = autoEnable.isSelected() ? enableNew(before) : 0;
			final String done = (names == null ? "Reloaded all" : "Reloaded " + String.join(", ", names))
				+ (enabled == 0 ? "" : ", started " + enabled);
			setStatus(done);
			sendChatMessage(done);
			refreshList();
		}));
	}


	/**
	 * @return the plugins currently loaded from the side-load directory, identified by their class
	 * loader rather than by name, which is the only thing that reliably distinguishes them
	 */
	private Set<Plugin> sideLoaded()
	{
		final Set<Plugin> plugins = new HashSet<>();
		for (Plugin plugin : pluginManager.getPlugins())
		{
			final ClassLoader loader = plugin.getClass().getClassLoader();
			if (loader != null && "PluginClassLoader".equals(loader.getClass().getSimpleName()))
			{
				plugins.add(plugin);
			}
		}

		return plugins;
	}

	/**
	 * Enables and starts whatever the reload brought in that was not loaded before, so a freshly built
	 * plugin runs without a trip to the plugin list. Plugins that were already there are left alone,
	 * including ones deliberately switched off.
	 */
	private int enableNew(Set<Plugin> before)
	{
		int started = 0;
		for (Plugin plugin : sideLoaded())
		{
			if (before.contains(plugin) || pluginManager.isPluginEnabled(plugin))
			{
				continue;
			}

			try
			{
				pluginManager.setPluginEnabled(plugin, true);
				if (pluginManager.startPlugin(plugin))
				{
					started++;
				}
			}
			catch (PluginInstantiationException ex)
			{
				log.warn("unable to start {}", plugin.getClass().getSimpleName(), ex);
			}
		}

		return started;
	}

	/**
	 * Reloads jars whose file has changed on disk since the list was last refreshed, so rebuilding a
	 * plugin is enough to see it in the client.
	 */
	private void checkForChanges()
	{
		if (!autoReload.isSelected())
		{
			return;
		}

		final File[] files = SIDELOADED_PLUGINS.listFiles((dir, name) -> name.endsWith(".jar"));
		if (files == null)
		{
			return;
		}

		// Jars that have gone away since the last poll. Their plugins are still loaded, and only a full
		// reload unloads them.
		final Set<String> present = new HashSet<>();
		for (File file : files)
		{
			present.add(file.getName());
		}

		boolean removed = lastModified.keySet().retainAll(present);
		settling.keySet().retainAll(present);

		final List<String> changed = new ArrayList<>();
		for (File file : files)
		{
			final String name = file.getName();
			final String stamp = file.lastModified() + ":" + file.length();
			final Long previous = lastModified.get(name);

			if (previous == null || previous == file.lastModified())
			{
				settling.remove(name);
				continue;
			}

			// Changed since the last reload. Only act once it has disabled changing, or a jar caught
			// half written would be loaded and fail to open.
			if (stamp.equals(settling.get(name)))
			{
				lastModified.put(name, file.lastModified());
				settling.remove(name);
				changed.add(name);
			}
			else
			{
				settling.put(name, stamp);
			}
		}

		if (removed)
		{
			// Reloading everything is the only way to unload a plugin whose jar is gone
			log.debug("side-loaded jar removed, reloading all");
			reload(null);
			return;
		}

		if (!changed.isEmpty())
		{
			log.debug("auto reloading changed side-loaded plugins {}", changed);
			reload(changed);
		}
	}

	private void openFolder()
	{
		try
		{
			if (!Desktop.isDesktopSupported())
			{
				setStatus("Desktop not supported, folder is " + SIDELOADED_PLUGINS);
				return;
			}

			Desktop.getDesktop().open(SIDELOADED_PLUGINS);
		}
		catch (IOException | UnsupportedOperationException ex)
		{
			log.warn("unable to open {}", SIDELOADED_PLUGINS, ex);
			setStatus("Unable to open " + SIDELOADED_PLUGINS);
		}
	}

	/**
	 * Mirrors the result into game chat, so a reload triggered while the window is behind the client
	 * still gives feedback where you are looking.
	 */
	private void sendChatMessage(String message)
	{
		// Posting chat before login makes every subscriber that assumes a local player throw
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT)
				.append(message)
				.append(ChatColorType.NORMAL)
				.build())
			.build());
	}

	private void setStatus(String text)
	{
		SwingUtilities.invokeLater(() -> status.setText(" " + text));
	}

	@Override
	public void open()
	{
		refreshList();
		watcher.start();
		super.open();
	}

	@Override
	public void close()
	{
		super.close();
		watcher.stop();
	}
}
