package com.redactado.noray.data;

import com.redactado.noray.data.codec.Codecs;
import com.redactado.noray.data.placement.Tier;
import com.redactado.noray.data.redundancy.Redundancy;

import java.util.List;
import java.util.Objects;

/**
 * How the data engine stores a file. Every chunk is written once per {@link Variant}; each variant is
 * compressed with its own codec, encrypted, and stored on the nodes of its tier with its own redundancy.
 *
 * @param chunkSize         plaintext bytes per chunk (the unit of placement and of erasure coding)
 * @param variants          at least one; the engine reads them in tier order (HOT first)
 * @param metadataReplicas  copies kept of every manifest page
 * @param ecMinCipherLen    chunks smaller than this are never erasure coded (shards would be tiny): they get
 *                          {@code m+1} full copies instead, which tolerates the same number of losses
 * @param padding           pad compressed chunks to size buckets, hiding their exact compressed length
 */
public record StoragePolicy(
        int chunkSize,
        List<Variant> variants,
        int metadataReplicas,
        int ecMinCipherLen,
        boolean padding) {

    /** One way of storing every chunk. */
    public record Variant(Tier tier, int codecId, Redundancy redundancy) {
        public Variant {
            Objects.requireNonNull(tier, "tier");
            Objects.requireNonNull(redundancy, "redundancy");
            Codecs.byId(codecId); // fail early on unknown codecs
        }
    }

    public StoragePolicy {
        if (chunkSize < 1024) {
            throw new IllegalArgumentException("chunkSize must be >= 1024");
        }
        variants = List.copyOf(Objects.requireNonNull(variants, "variants"));
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("at least one variant is required");
        }
        if (metadataReplicas < 1) {
            throw new IllegalArgumentException("metadataReplicas must be >= 1");
        }
    }

    public static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024;

    /**
     * Raw storage this policy needs for {@code logicalBytes} of content across all variants, before compression
     * (which only lowers it) and padding (at most 12.5 % more). Replicas cost {@code n}x, erasure coding {@code (k+m)/k}x.
     * Manifest pages are ignored (a few hundred bytes per 4 MiB chunk).
     */
    public long storedBytesEstimate(long logicalBytes) {
        double factor = 0;
        for (Variant v : variants) {
            factor += (double) v.redundancy().totalBlobs() / v.redundancy().minToRead();
        }
        return (long) Math.ceil(Math.max(0, logicalBytes) * factor);
    }

    /**
     * The recommended layout for a cluster with principal and capacity nodes: two fast-compressed full copies on
     * HOT nodes plus a 4+4 erasure-coded, aggressively compressed copy on COLD nodes (tolerates 4 lost shards).
     */
    public static StoragePolicy tiered() {
        return new StoragePolicy(DEFAULT_CHUNK_SIZE, List.of(
                new Variant(Tier.HOT, Codecs.DEFLATE_FAST_ID, Redundancy.replicas(2)),
                new Variant(Tier.COLD, Codecs.DEFLATE_MAX_ID, Redundancy.erasure(4, 4))),
                5, 256 * 1024, true); // the manifest must outlive as many nodes as the data (m + 1 copies)
    }

    /** Everything on COLD capacity nodes, erasure coded: the smallest footprint per byte of redundancy. */
    public static StoragePolicy archive(int k, int m) {
        return new StoragePolicy(DEFAULT_CHUNK_SIZE, List.of(
                new Variant(Tier.COLD, Codecs.DEFLATE_MAX_ID, Redundancy.erasure(k, m))),
                Math.max(3, m + 1), 256 * 1024, true); // the manifest must outlive as many nodes as the data
    }

    /** Minimal layout for development: one fast-compressed copy on a HOT node. */
    public static StoragePolicy single() {
        return new StoragePolicy(DEFAULT_CHUNK_SIZE, List.of(
                new Variant(Tier.HOT, Codecs.DEFLATE_FAST_ID, Redundancy.replicas(1))),
                1, 256 * 1024, true);
    }

    public StoragePolicy withChunkSize(int newChunkSize) {
        return new StoragePolicy(newChunkSize, variants, metadataReplicas, ecMinCipherLen, padding);
    }

    public StoragePolicy withMetadataReplicas(int copies) {
        return new StoragePolicy(chunkSize, variants, copies, ecMinCipherLen, padding);
    }

    public StoragePolicy withPadding(boolean enabled) {
        return new StoragePolicy(chunkSize, variants, metadataReplicas, ecMinCipherLen, enabled);
    }

    public StoragePolicy withEcMinCipherLen(int bytes) {
        return new StoragePolicy(chunkSize, variants, metadataReplicas, bytes, padding);
    }
}
