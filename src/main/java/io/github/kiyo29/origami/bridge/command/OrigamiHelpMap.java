package io.github.kiyo29.origami.bridge.command;

import org.bukkit.help.HelpMap;
import org.bukkit.help.HelpTopic;
import org.bukkit.help.HelpTopicFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * HelpMap の最小実装。トピックの登録・取得だけを行う。
 * Bukkit のコマンドを自動で索引することと /help の公開は未対応 (COMPATIBILITY.md に記録)。
 * registerHelpTopicFactory は記録するだけで、現状は使わない。
 */
public final class OrigamiHelpMap implements HelpMap {
    private final Map<String, HelpTopic> topics = new ConcurrentHashMap<>();
    private final Map<Class<?>, HelpTopicFactory<?>> factories = new ConcurrentHashMap<>();
    private final List<String> ignoredPlugins = new CopyOnWriteArrayList<>();

    @Override
    public HelpTopic getHelpTopic(String topicName) {
        return topicName == null ? null : topics.get(topicName);
    }

    @Override
    public Collection<HelpTopic> getHelpTopics() {
        return List.copyOf(topics.values());
    }

    @Override
    public void addTopic(HelpTopic topic) {
        if (topic != null && topic.getName() != null) {
            topics.put(topic.getName(), topic);
        }
    }

    @Override
    public void clear() {
        topics.clear();
    }

    @Override
    public void registerHelpTopicFactory(Class<?> commandClass, HelpTopicFactory<?> factory) {
        if (commandClass != null && factory != null) {
            factories.put(commandClass, factory);
        }
    }

    @Override
    public List<String> getIgnoredPlugins() {
        return ignoredPlugins;
    }
}
