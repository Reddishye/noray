package com.redactado.noray.data.redundancy;

/** How many blobs a stored chunk variant is made of and how many are needed to read it. */
public sealed interface Redundancy {

    /** Number of blobs written (copies or shards). */
    int totalBlobs();

    /** Minimum number of blobs that must be readable to rebuild the data. */
    int minToRead();

    /** Number of lost blobs that can be survived. */
    default int tolerates() {
        return totalBlobs() - minToRead();
    }

    /** {@code n} full copies. */
    record Replicas(int n) implements Redundancy {
        public Replicas {
            if (n < 1 || n > 255) {
                throw new IllegalArgumentException("replicas must be 1..255");
            }
        }

        @Override public int totalBlobs() { return n; }
        @Override public int minToRead() { return 1; }
    }

    /** Reed-Solomon: {@code k} data shards + {@code m} parity shards, any {@code k} rebuild the chunk. */
    record ErasureCoded(int k, int m) implements Redundancy {
        public ErasureCoded {
            if (k < 1 || m < 0 || k + m > 255) {
                throw new IllegalArgumentException("need 1 <= k, 0 <= m, k + m <= 255");
            }
        }

        @Override public int totalBlobs() { return k + m; }
        @Override public int minToRead() { return k; }
    }

    static Redundancy replicas(int n) {
        return new Replicas(n);
    }

    static Redundancy erasure(int k, int m) {
        return new ErasureCoded(k, m);
    }
}
