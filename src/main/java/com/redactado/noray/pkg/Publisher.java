package com.redactado.noray.pkg;

import com.redactado.noray.identity.SigningKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A publisher the operator pinned: a name, the Ed25519 keys that sign for it, how many of them must agree, and whether
 * its packages may run inside the node's JVM ({@code trusted}). Without {@code trusted} only process-sandboxed packages
 * from it are accepted.
 */
public record Publisher(String name, List<byte[]> keys, int threshold, boolean trusted) {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public Publisher {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("publisher name must be short lower-case: " + name);
        }
        List<byte[]> copy = new ArrayList<>();
        for (byte[] k : keys) {
            if (k.length != 32) throw new IllegalArgumentException("a publisher key is 32 bytes");
            if (copy.stream().anyMatch(x -> Arrays.equals(x, k))) throw new IllegalArgumentException("duplicate key");
            copy.add(k.clone());
        }
        if (copy.isEmpty() || copy.size() > PackageManifest.MAX_SIGNATURES) {
            throw new IllegalArgumentException("a publisher has 1.." + PackageManifest.MAX_SIGNATURES + " keys");
        }
        if (threshold < 1 || threshold > copy.size()) {
            throw new IllegalArgumentException("threshold must be between 1 and the number of keys");
        }
        keys = List.copyOf(copy);
    }

    /** Number of distinct keys of this publisher with a valid signature over the manifest body. */
    public int validSignatures(PackageManifest manifest) {
        byte[] body = manifest.body();
        int n = 0;
        for (PackageManifest.Signature s : manifest.signatures()) {
            boolean ours = keys.stream().anyMatch(k -> Arrays.equals(k, s.key()));
            if (ours && SigningKey.verify(s.key(), PackageManifest.SIGNATURE_DOMAIN, body, s.signature())) {
                n++; // the manifest parser already refuses two signatures by one key
            }
        }
        return n;
    }

    public List<String> keysHex() {
        return keys.stream().map(k -> HexFormat.of().formatHex(k)).toList();
    }
}
