package com.redactado.noray.data.crypto;

import com.redactado.noray.kernel.api.KernelContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Opens the node's keyring from the configuration; shared by the modules that need it (network keys, storage). */
public final class KeyringProvider {

    /** Below this, a passphrase is too cheap to attack (OWASP's minimum for Argon2id is 19 MiB, 2 passes). */
    public static final int MIN_MEMORY_KIB = 19 * 1024;
    public static final int MIN_ITERATIONS = 2;

    private KeyringProvider() {
    }

    /**
     * Unlocks (or on first use creates) the keyring when a passphrase is configured: {@code noray.keyring.passphrase.file}
     * (preferred), the {@code NORAY_PASSPHRASE} environment variable, or {@code noray.keyring.passphrase}
     * (discouraged: configuration values stay in memory). Returns null when encryption is not configured.
     * New keyrings refuse Argon2id costs under {@link #MIN_MEMORY_KIB}/{@link #MIN_ITERATIONS} unless
     * {@code noray.keyring.argon2.insecure=true} (tests).
     */
    public static Keyring open(KernelContext context, Path dataDir) {
        String explicit = context.getConfiguration().get("noray.keyring.file", "");
        Path file = explicit != null && !explicit.isBlank() ? Path.of(explicit) : dataDir.resolve("keyring");
        char[] passphrase = readPassphrase(context);
        if (passphrase == null) {
            if (Keyring.exists(file)) {
                throw new IllegalStateException("keyring " + file + " exists but no passphrase is configured "
                        + "(noray.keyring.passphrase.file or NORAY_PASSPHRASE)");
            }
            return null;
        }
        Keyring.Params params = new Keyring.Params(
                context.getConfiguration().getInt("noray.keyring.argon2.memory.kib", Keyring.Params.DEFAULT.memoryKiB()),
                context.getConfiguration().getInt("noray.keyring.argon2.iterations", Keyring.Params.DEFAULT.iterations()),
                Keyring.Params.DEFAULT.parallelism());
        if (!Keyring.exists(file) && !context.getConfiguration().getBoolean("noray.keyring.argon2.insecure", false)
                && (params.memoryKiB() < MIN_MEMORY_KIB || params.iterations() < MIN_ITERATIONS)) {
            throw new IllegalStateException("Argon2id cost too low for a passphrase (" + params.memoryKiB() + " KiB, "
                    + params.iterations() + " passes): at least " + MIN_MEMORY_KIB + " KiB and " + MIN_ITERATIONS + " passes");
        }
        try {
            return Keyring.openOrCreate(file, passphrase, params);
        } finally {
            Arrays.fill(passphrase, '\0');
        }
    }

    private static char[] readPassphrase(KernelContext context) {
        String path = context.getConfiguration().get("noray.keyring.passphrase.file", "");
        if (path != null && !path.isBlank()) {
            try {
                String text = Files.readString(Path.of(path));
                int end = text.length();
                while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
                    end--;
                }
                return text.substring(0, end).toCharArray();
            } catch (IOException e) {
                throw new IllegalStateException("cannot read passphrase file " + path, e);
            }
        }
        String env = System.getenv("NORAY_PASSPHRASE");
        if (env != null && !env.isEmpty()) {
            return env.toCharArray();
        }
        String direct = context.getConfiguration().get("noray.keyring.passphrase", "");
        return direct != null && !direct.isEmpty() ? direct.toCharArray() : null;
    }
}
