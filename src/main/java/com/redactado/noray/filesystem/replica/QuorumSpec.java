package com.redactado.noray.filesystem.replica;

public record QuorumSpec(int n, int w, int r) {
    public QuorumSpec {
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1, got " + n);
        }
        if (w < 1 || w > n) {
            throw new IllegalArgumentException("w must be between 1 and n, got w=" + w + ", n=" + n);
        }
        if (r < 1 || r > n) {
            throw new IllegalArgumentException("r must be between 1 and n, got r=" + r + ", n=" + n);
        }
    }
}
