package com.redactado.noray.data.redundancy;

/**
 * Systematic Reed-Solomon erasure code over GF(2^8): {@code k} data shards plus {@code m} parity shards,
 * any {@code k} of the {@code k+m} shards reconstruct the data.
 *
 * <p>Shards are opaque byte arrays of equal length. Callers must encrypt <em>before</em> encoding: the
 * first {@code k} shards are slices of the input in the clear.
 */
public final class ReedSolomon {

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];
    private static final byte[][] MUL = new byte[256][256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) {
                x ^= 0x11d;
            }
        }
        for (int i = 255; i < 512; i++) {
            EXP[i] = EXP[i - 255];
        }
        for (int a = 0; a < 256; a++) {
            for (int b = 0; b < 256; b++) {
                MUL[a][b] = (byte) mul(a, b);
            }
        }
    }

    private final int k;
    private final int m;
    /** (k+m) x k encoding matrix whose top k rows are the identity. */
    private final int[][] matrix;

    public ReedSolomon(int dataShards, int parityShards) {
        if (dataShards < 1 || parityShards < 0 || dataShards + parityShards > 255) {
            throw new IllegalArgumentException("need 1 <= k, 0 <= m, k + m <= 255");
        }
        this.k = dataShards;
        this.m = parityShards;
        int n = k + m;
        int[][] vand = new int[n][k];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < k; c++) {
                vand[r][c] = pow(r, c);
            }
        }
        int[][] top = new int[k][];
        for (int i = 0; i < k; i++) {
            top[i] = vand[i].clone();
        }
        this.matrix = multiply(vand, invert(top));
    }

    public int dataShards() {
        return k;
    }

    public int parityShards() {
        return m;
    }

    /** Computes the {@code m} parity shards of the given {@code k} equal-length data shards. */
    public byte[][] encode(byte[][] data) {
        if (data.length != k) {
            throw new IllegalArgumentException("expected " + k + " data shards");
        }
        int len = data[0].length;
        byte[][] parity = new byte[m][len];
        for (int p = 0; p < m; p++) {
            for (int d = 0; d < k; d++) {
                addScaled(parity[p], data[d], matrix[k + p][d], len);
            }
        }
        return parity;
    }

    /**
     * Rebuilds the {@code k} data shards.
     *
     * @param shards   array of {@code k+m} entries in shard order; missing shards are {@code null}
     * @param shardLen length of every present shard
     * @throws IllegalArgumentException if fewer than {@code k} shards are present
     */
    public byte[][] decode(byte[][] shards, int shardLen) {
        if (shards.length != k + m) {
            throw new IllegalArgumentException("expected " + (k + m) + " shard slots");
        }
        int[] rows = new int[k];
        int found = 0;
        boolean allDataPresent = true;
        for (int i = 0; i < k; i++) {
            if (shards[i] == null) {
                allDataPresent = false;
            }
        }
        if (allDataPresent) {
            byte[][] out = new byte[k][];
            for (int i = 0; i < k; i++) {
                out[i] = shards[i];
            }
            return out;
        }
        for (int i = 0; i < k + m && found < k; i++) {
            if (shards[i] != null) {
                if (shards[i].length != shardLen) {
                    throw new IllegalArgumentException("shard " + i + " has the wrong length");
                }
                rows[found++] = i;
            }
        }
        if (found < k) {
            throw new IllegalArgumentException("need " + k + " shards, only " + found + " available");
        }
        int[][] sub = new int[k][];
        for (int i = 0; i < k; i++) {
            sub[i] = matrix[rows[i]].clone();
        }
        int[][] inv = invert(sub);
        byte[][] out = new byte[k][shardLen];
        for (int d = 0; d < k; d++) {
            if (shards[d] != null) {
                out[d] = shards[d];
                continue;
            }
            for (int c = 0; c < k; c++) {
                addScaled(out[d], shards[rows[c]], inv[d][c], shardLen);
            }
        }
        return out;
    }

    // ── GF(2^8) arithmetic ─────────────────────────────────────────────────────

    private static int mul(int a, int b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        return EXP[LOG[a] + LOG[b]];
    }

    private static int inv(int a) {
        if (a == 0) {
            throw new ArithmeticException("division by zero in GF(256)");
        }
        return EXP[255 - LOG[a]];
    }

    private static int pow(int base, int exp) {
        if (exp == 0) {
            return 1;
        }
        if (base == 0) {
            return 0;
        }
        return EXP[(LOG[base] * exp) % 255];
    }

    /** dst ^= coef * src, byte-wise in GF(256). */
    private static void addScaled(byte[] dst, byte[] src, int coef, int len) {
        if (coef == 0) {
            return;
        }
        if (coef == 1) {
            for (int i = 0; i < len; i++) {
                dst[i] ^= src[i];
            }
            return;
        }
        byte[] row = MUL[coef];
        for (int i = 0; i < len; i++) {
            dst[i] ^= row[src[i] & 0xff];
        }
    }

    private static int[][] multiply(int[][] a, int[][] b) {
        int rows = a.length;
        int inner = b.length;
        int cols = b[0].length;
        int[][] out = new int[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int v = 0;
                for (int i = 0; i < inner; i++) {
                    v ^= mul(a[r][i], b[i][c]);
                }
                out[r][c] = v;
            }
        }
        return out;
    }

    /** Gauss-Jordan inversion; the input is consumed. */
    private static int[][] invert(int[][] a) {
        int n = a.length;
        int[][] id = new int[n][n];
        for (int i = 0; i < n; i++) {
            id[i][i] = 1;
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            while (pivot < n && a[pivot][col] == 0) {
                pivot++;
            }
            if (pivot == n) {
                throw new ArithmeticException("matrix is singular");
            }
            int[] tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp;
            tmp = id[col]; id[col] = id[pivot]; id[pivot] = tmp;

            int scale = inv(a[col][col]);
            for (int j = 0; j < n; j++) {
                a[col][j] = mul(a[col][j], scale);
                id[col][j] = mul(id[col][j], scale);
            }
            for (int r = 0; r < n; r++) {
                if (r != col && a[r][col] != 0) {
                    int f = a[r][col];
                    for (int j = 0; j < n; j++) {
                        a[r][j] ^= mul(f, a[col][j]);
                        id[r][j] ^= mul(f, id[col][j]);
                    }
                }
            }
        }
        return id;
    }

    @Override
    public String toString() {
        return "ReedSolomon(" + k + "+" + m + ")";
    }
}
