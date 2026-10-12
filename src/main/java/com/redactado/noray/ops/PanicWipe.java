package com.redactado.noray.ops;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Emergency destruction of this node's secrets (crypto-shredding). Without the keyring, the encrypted namespace journal,
 * the file keys it holds and the vault copies on other nodes are unreadable; without the node keys the node cannot be
 * impersonated. Files are overwritten with random bytes, synced and deleted, keys first.
 *
 * <p>Honest limits: on copy-on-write filesystems (btrfs, ZFS) and on SSDs (wear levelling) an overwrite does not reach the
 * old physical blocks, so this is only reliable together with full-disk encryption whose key is gone when the machine is
 * off. A passphrase file outside the data directory (e.g. /etc/noray/passphrase) is not reachable by the node's user and must
 * be destroyed separately. Data already copied elsewhere in plaintext is out of reach by definition.
 */
public final class PanicWipe {

    /** What was destroyed and what could not be. */
    public record Report(List<Path> shredded, List<String> failures) {
    }

    /** Key material first (relative to the data directory), then everything else. */
    static final List<String> KEYS_FIRST = List.of("keyring", "net/local.keys.sealed", "net/local.keys", "net/local.id", "identity", "web/tokens",
            "packages/publishers.properties", "fs/namespace.journal", "fs/heat.bin");

    private static final SecureRandom RANDOM = new SecureRandom();

    private PanicWipe() {
    }

    /** @param everything also shred the rest of the data directory (local blob stores, packages, configuration) */
    public static Report wipe(Path dataDir, boolean everything) {
        List<Path> order = new ArrayList<>();
        for (String k : KEYS_FIRST) {
            Path p = dataDir.resolve(k);
            if (Files.isDirectory(p)) order.addAll(files(p));
            else if (Files.isRegularFile(p)) order.add(p);
        }
        if (everything) {
            for (Path p : files(dataDir)) if (!order.contains(p)) order.add(p);
        }
        List<Path> done = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Path p : order) {
            try {
                shred(p);
                done.add(p);
            } catch (IOException | RuntimeException e) {
                failures.add(p + ": " + e.getMessage());
            }
        }
        return new Report(done, failures);
    }

    static void shred(Path p) throws IOException {
        long size = Files.size(p);
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[64 * 1024];
            for (int pass = 0; pass < 2; pass++) {
                ch.position(0);
                long left = size;
                while (left > 0) {
                    RANDOM.nextBytes(buf);
                    int n = (int) Math.min(buf.length, left);
                    ch.write(ByteBuffer.wrap(buf, 0, n));
                    left -= n;
                }
                ch.force(true);
            }
        }
        Path renamed = p.resolveSibling(".shred-" + Long.toHexString(RANDOM.nextLong())); // the name goes too
        Files.move(p, renamed);
        Files.delete(renamed);
    }

    private static List<Path> files(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
