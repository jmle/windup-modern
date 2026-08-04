package org.jboss.windup.model;

import java.util.*;
import java.util.function.Function;

public class ModelRegistry<T> {

    private final List<T> items = new ArrayList<>();
    private final Map<String, Index<T>> indexes = new LinkedHashMap<>();

    public void register(T item) {
        items.add(item);
        for (Index<T> index : indexes.values()) {
            index.add(item);
        }
    }

    public List<T> findAll() {
        return Collections.unmodifiableList(items);
    }

    public int size() {
        return items.size();
    }

    public void addIndex(String name, Function<T, Object> extractor) {
        Index<T> index = new Index<>(extractor);
        for (T item : items) {
            index.add(item);
        }
        indexes.put(name, index);
    }

    public List<T> findByIndex(String indexName, Object key) {
        Index<T> index = indexes.get(indexName);
        if (index == null) return List.of();
        return index.find(key);
    }

    public Optional<T> findUniqueByIndex(String indexName, Object key) {
        List<T> results = findByIndex(indexName, key);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    private static class Index<T> {
        private final Function<T, Object> extractor;
        private final Map<Object, List<T>> map = new HashMap<>();

        Index(Function<T, Object> extractor) {
            this.extractor = extractor;
        }

        void add(T item) {
            Object key = extractor.apply(item);
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }

        List<T> find(Object key) {
            return map.getOrDefault(key, List.of());
        }
    }
}
