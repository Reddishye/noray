package com.redactado.noray.data.tier;

import com.redactado.noray.data.StoragePolicy;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named storage policies, and "auto" profiles that move a file between two of them by how much it is used.
 *
 * <p>Folders and mounts refer to a policy by name ({@code tiered}, {@code archive}, {@code auto}, ...); the name
 * is what gets persisted, so renaming a policy later orphans nothing: the manifest of each stored file records
 * how it was actually stored.
 */
public final class PolicyCatalog {

    /**
     * Moves files between {@code hot} and {@code cold} (names of plain policies) with hysteresis: a file is demoted
     * when it has been idle for {@code demoteAfterIdle} and its access rate fell below {@code demoteBelowPerDay},
     * and promoted again once the rate rises above {@code promoteAbovePerDay}.
     */
    public record AutoProfile(String hot, String cold, double promoteAbovePerDay, double demoteBelowPerDay,
                              Duration demoteAfterIdle) {
        public AutoProfile {
            Objects.requireNonNull(hot, "hot");
            Objects.requireNonNull(cold, "cold");
            Objects.requireNonNull(demoteAfterIdle, "demoteAfterIdle");
            if (promoteAbovePerDay <= demoteBelowPerDay || demoteBelowPerDay < 0) {
                throw new IllegalArgumentException("promote threshold must be above the demote threshold (hysteresis)");
            }
        }
    }

    public static final String DEFAULT_POLICY = "tiered";

    private final Map<String, StoragePolicy> policies = new ConcurrentHashMap<>();
    private final Map<String, AutoProfile> autos = new ConcurrentHashMap<>();

    /** {@code tiered}, {@code archive} (4+4 on cold), {@code single} (one hot copy) and {@code auto}. */
    public static PolicyCatalog defaults() {
        PolicyCatalog c = new PolicyCatalog();
        c.register("tiered", StoragePolicy.tiered());
        c.register("archive", StoragePolicy.archive(4, 4));
        c.register("single", StoragePolicy.single());
        c.registerAuto("auto", new AutoProfile("tiered", "archive", 3.0, 0.5, Duration.ofDays(7)));
        return c;
    }

    public void register(String name, StoragePolicy policy) {
        checkName(name);
        policies.put(name, Objects.requireNonNull(policy, "policy"));
    }

    public void registerAuto(String name, AutoProfile profile) {
        checkName(name);
        Objects.requireNonNull(profile, "profile");
        if (!policies.containsKey(profile.hot()) || !policies.containsKey(profile.cold())) {
            throw new IllegalArgumentException("auto profile refers to unknown policies");
        }
        autos.put(name, profile);
    }

    private static void checkName(String name) {
        if (name == null || !name.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid policy name: " + name);
        }
    }

    public boolean known(String name) {
        return policies.containsKey(name) || autos.containsKey(name);
    }

    public Optional<StoragePolicy> policy(String name) {
        return Optional.ofNullable(policies.get(name));
    }

    public Optional<AutoProfile> auto(String name) {
        return Optional.ofNullable(autos.get(name));
    }

    /** The plain policy new data is written with: the policy itself, or the hot side of an auto profile. */
    public StoragePolicy forWrite(String name) {
        AutoProfile a = autos.get(name);
        String plain = a != null ? a.hot() : name;
        StoragePolicy p = policies.get(plain);
        if (p == null) {
            throw new IllegalArgumentException("unknown storage policy: " + name);
        }
        return p;
    }

    /** Name of the plain policy new data is written with. */
    public String writeName(String name) {
        AutoProfile a = autos.get(name);
        return a != null ? a.hot() : name;
    }

    public Map<String, String> describe() {
        Map<String, String> out = new TreeMap<>();
        policies.forEach((n, p) -> out.put(n, p.variants().stream()
                .map(v -> v.tier() + ":" + v.redundancy()).toList().toString()));
        autos.forEach((n, a) -> out.put(n, "auto " + a.hot() + " <-> " + a.cold()));
        return out;
    }
}
