package io.github.kiyo29.origami.loader;

import io.github.kiyo29.origami.internal.Lang;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * depend / softdepend / loadbefore / provides から読み込み順を決める。
 * 依存先 → 依存元の順に並べ、同じ段の中は名前順 (結果を毎回同じにするため)。
 * 次のものは理由つきで除外する: 名前の重複、depend の不足 (依存先が除外された場合も含む)、循環。
 */
final class DependencyResolver {
    private static final Comparator<PluginCandidate> BY_NAME =
            Comparator.comparing((PluginCandidate c) -> key(c)).thenComparing(c -> c.file().getName());

    record Result(List<PluginCandidate> ordered, Map<PluginCandidate, String> rejected) { }

    private DependencyResolver() { }

    /** @param available 既に読み込み済みのプラグイン名 (provides を含む、小文字) */
    static Result resolve(List<PluginCandidate> candidates, Set<String> available) {
        Map<PluginCandidate, String> rejected = new LinkedHashMap<>();
        List<PluginCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(BY_NAME);

        List<PluginCandidate> live = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (PluginCandidate c : sorted) {
            String key = key(c);
            if (available.contains(key) || !names.add(key)) {
                rejected.put(c, Lang.tr("log.loader.reason.duplicate", c.description().getName()));
            } else {
                live.add(c);
            }
        }

        while (true) {
            removeMissing(live, available, rejected);

            Map<String, PluginCandidate> index = index(live);
            Map<PluginCandidate, Set<PluginCandidate>> out = new HashMap<>();
            Map<PluginCandidate, Integer> inDegree = new HashMap<>();
            for (PluginCandidate c : live) {
                out.put(c, new LinkedHashSet<>());
                inDegree.put(c, 0);
            }
            for (PluginCandidate c : live) {
                List<String> before = new ArrayList<>(c.description().getPluginDependencies());
                before.addAll(c.description().getPluginSoftDependencies());
                for (String dep : before) {
                    PluginCandidate target = index.get(dep.toLowerCase(Locale.ROOT));
                    if (target != null && target != c) {
                        addEdge(out, inDegree, target, c);
                    }
                }
                for (String name : c.description().getLoadBeforePlugins()) {
                    PluginCandidate target = index.get(name.toLowerCase(Locale.ROOT));
                    if (target != null && target != c) {
                        addEdge(out, inDegree, c, target);
                    }
                }
            }

            PriorityQueue<PluginCandidate> ready = new PriorityQueue<>(BY_NAME);
            for (PluginCandidate c : live) {
                if (inDegree.get(c) == 0) {
                    ready.add(c);
                }
            }
            List<PluginCandidate> ordered = new ArrayList<>();
            while (!ready.isEmpty()) {
                PluginCandidate c = ready.poll();
                ordered.add(c);
                for (PluginCandidate next : out.get(c)) {
                    if (inDegree.merge(next, -1, Integer::sum) == 0) {
                        ready.add(next);
                    }
                }
            }
            if (ordered.size() == live.size()) {
                return new Result(ordered, rejected);
            }
            // 循環に含まれるものを除外し、それに依存していたものを再判定する
            Set<PluginCandidate> done = new HashSet<>(ordered);
            for (Iterator<PluginCandidate> it = live.iterator(); it.hasNext(); ) {
                PluginCandidate c = it.next();
                if (!done.contains(c)) {
                    rejected.put(c, Lang.tr("log.loader.reason.cycle"));
                    it.remove();
                }
            }
        }
    }

    private static void removeMissing(List<PluginCandidate> live, Set<String> available,
                                      Map<PluginCandidate, String> rejected) {
        boolean changed;
        do {
            changed = false;
            Map<String, PluginCandidate> index = index(live);
            for (Iterator<PluginCandidate> it = live.iterator(); it.hasNext(); ) {
                PluginCandidate c = it.next();
                List<String> missing = new ArrayList<>();
                for (String dep : c.description().getPluginDependencies()) {
                    String k = dep.toLowerCase(Locale.ROOT);
                    if (!index.containsKey(k) && !available.contains(k)) {
                        missing.add(dep);
                    }
                }
                if (!missing.isEmpty()) {
                    rejected.put(c, Lang.tr("log.loader.reason.missing_dep", String.join(", ", missing)));
                    it.remove();
                    changed = true;
                }
            }
        } while (changed);
    }

    /** 名前 (小文字) と provides から候補を引く表。名前が優先。 */
    private static Map<String, PluginCandidate> index(List<PluginCandidate> live) {
        Map<String, PluginCandidate> index = new HashMap<>();
        for (PluginCandidate c : live) {
            index.put(key(c), c);
        }
        for (PluginCandidate c : live) {
            for (String provided : c.description().getProvidedPlugins()) {
                index.putIfAbsent(provided.toLowerCase(Locale.ROOT), c);
            }
        }
        return index;
    }

    private static void addEdge(Map<PluginCandidate, Set<PluginCandidate>> out,
                                Map<PluginCandidate, Integer> inDegree, PluginCandidate from, PluginCandidate to) {
        if (out.get(from).add(to)) {
            inDegree.merge(to, 1, Integer::sum);
        }
    }

    private static String key(PluginCandidate c) {
        return c.description().getName().toLowerCase(Locale.ROOT);
    }
}
