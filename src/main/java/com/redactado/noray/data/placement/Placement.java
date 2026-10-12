package com.redactado.noray.data.placement;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Deterministic placement by weighted rendezvous (highest random weight) hashing.
 *
 * <p>Every participant computes the same ordered candidate list for a key without any shared table, adding or
 * removing a node only moves the data that belonged to it, and the weight of a node scales its share.
 * The list is arranged so that the first candidates come from distinct failure domains.
 */
public final class Placement {

    private Placement() {
    }

    /**
     * Orders {@code nodes} for {@code key}: first one node per failure domain (best-ranked first), then the
     * remaining nodes by rank. Taking the first {@code n} entries therefore spreads over as many domains as exist.
     */
    public static List<StorageNode> candidates(List<? extends StorageNode> nodes, String key) {
        return candidates(nodes, key, StorageNode::capacityWeight);
    }

    /** Same as {@link #candidates(List, String)} with an explicit weight function (for example per data class). */
    public static List<StorageNode> candidates(List<? extends StorageNode> nodes, String key,
                                               ToDoubleFunction<StorageNode> weight) {
        // score every node once (a SHA-256 each), not once per comparison
        Map<StorageNode, Double> scores = new IdentityHashMap<>();
        for (StorageNode n : nodes) {
            scores.put(n, score(n, key, weight.applyAsDouble(n)));
        }
        List<StorageNode> ranked = new ArrayList<>(nodes);
        ranked.sort(Comparator.comparingDouble((StorageNode n) -> scores.get(n)).thenComparing(StorageNode::id));

        List<StorageNode> ordered = new ArrayList<>(ranked.size());
        List<StorageNode> rest = new ArrayList<>();
        Set<String> seenDomains = new HashSet<>();
        for (StorageNode n : ranked) {
            if (seenDomains.add(n.domain())) {
                ordered.add(n);
            } else {
                rest.add(n);
            }
        }
        ordered.addAll(rest);
        return ordered;
    }

    /** Lower is better. {@code -ln(u) / weight} with u uniform in (0,1) makes shares proportional to weight. */
    static double score(StorageNode node, String key, double weight) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(node.id().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(key.getBytes(StandardCharsets.UTF_8));
            long bits = ByteBuffer.wrap(md.digest()).getLong() >>> 11; // 53 bits
            double u = (bits + 0.5) / (double) (1L << 53);
            return -Math.log(u) / weight;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
