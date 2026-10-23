package org.rx.crawler.task.price;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Access-order cache with write expiry and hard count/weight bounds. Never serialized. */
public final class BoundedMemoryCache<T> {
    private record Entry<T>(T value, long expires, int weight) {}
    private final LinkedHashMap<String, Entry<T>> entries = new LinkedHashMap<>(16, .75f, true);
    private final int maxEntries;
    private final int maxWeight;
    private final LongSupplier time;
    private int weight;

    public BoundedMemoryCache(int maxEntries, int maxWeight) {
        this(maxEntries, maxWeight, System::currentTimeMillis);
    }

    BoundedMemoryCache(int maxEntries, int maxWeight, LongSupplier time) {
        if (maxEntries <= 0 || maxWeight <= 0) throw new IllegalArgumentException("Positive cache bounds required");
        this.maxEntries = maxEntries;
        this.maxWeight = maxWeight;
        this.time = time;
    }

    public synchronized T get(String key) {
        Entry<T> entry = entries.get(key);
        if (entry == null) return null;
        if (entry.expires <= time.getAsLong()) { remove(key); return null; }
        return entry.value;
    }

    public synchronized void put(String key, T value, Duration ttl, int valueWeight) {
        remove(key);
        int cost = Math.max(1, valueWeight) + key.length() * 2 + 64;
        if (cost > maxWeight || ttl.isNegative() || ttl.isZero()) return;
        purge();
        Iterator<Map.Entry<String, Entry<T>>> it = entries.entrySet().iterator();
        while (it.hasNext() && (entries.size() >= maxEntries || weight + cost > maxWeight)) {
            weight -= it.next().getValue().weight;
            it.remove();
        }
        entries.put(key, new Entry<>(value, time.getAsLong() + ttl.toMillis(), cost));
        weight += cost;
    }

    public synchronized int size() { purge(); return entries.size(); }
    public synchronized int weight() { purge(); return weight; }
    public synchronized void remove(String key) {
        Entry<T> old = entries.remove(key);
        if (old != null) weight -= old.weight;
    }
    private void purge() {
        long now = time.getAsLong();
        entries.entrySet().removeIf(entry -> {
            if (entry.getValue().expires > now) return false;
            weight -= entry.getValue().weight;
            return true;
        });
    }
}
