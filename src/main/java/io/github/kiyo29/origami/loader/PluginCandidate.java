package io.github.kiyo29.origami.loader;

import org.bukkit.plugin.PluginDescriptionFile;

import java.io.File;

/** plugins/ で見つかった jar と、その plugin.yml の内容。 */
record PluginCandidate(File file, PluginDescriptionFile description) { }
