package com.redactado.noray.kernel.capability;

import java.util.*;

/** Grants per sandboxed module and a bounded record of refusals. */
public final class CapabilityRegistry {

    public record Denial(long timeMillis, String moduleId, String capability) {}

    private static final int MAX_DENIALS = 1000;

    private final Map<String, CapabilitySet> grants = Collections.synchronizedMap(new TreeMap<>());
    private final Deque<Denial> denials = new ArrayDeque<>();
    private long denialCount;
    private final Map<Class<?>, ServiceAttenuator<?>> attenuators = new java.util.concurrent.ConcurrentHashMap<>();

    public <T> void registerAttenuator(ServiceAttenuator<T> attenuator) {
        attenuators.put(attenuator.serviceType(), attenuator);
    }

    @SuppressWarnings("unchecked")
    <T> ServiceAttenuator<T> attenuatorFor(Class<T> type) {
        return (ServiceAttenuator<T>) attenuators.get(type);
    }

    public void setGrants(String moduleId, CapabilitySet set) { grants.put(moduleId, set); }

    public Optional<CapabilitySet> grantsOf(String moduleId) { return Optional.ofNullable(grants.get(moduleId)); }

    public Set<String> sandboxedModules() {
        synchronized (grants) { return new TreeSet<>(grants.keySet()); }
    }

    public void deny(String moduleId, String capability) {
        synchronized (denials) {
            denialCount++;
            if (denials.size() == MAX_DENIALS) denials.removeFirst();
            denials.addLast(new Denial(System.currentTimeMillis(), moduleId, capability));
        }
    }

    public List<Denial> recentDenials(int max) {
        synchronized (denials) {
            List<Denial> all = new ArrayList<>(denials);
            return all.subList(Math.max(0, all.size() - max), all.size());
        }
    }

    public long denialCount() {
        synchronized (denials) { return denialCount; }
    }
}
