package com.redactado.noray.data.crypto;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.util.SecureFiles;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Holds the node's master key, derived from a passphrase with Argon2id. Only the salt, the cost parameters and
 * a verifier (to detect a wrong passphrase) are stored on disk; the master key and the passphrase never are.
 *
 * <p>Everything that must be encrypted at rest (namespace journal, access statistics, ...) uses a purpose-specific
 * sub-key from {@link #subKey(String)}, so compromising one use does not expose the others.
 *
 * <p>Seizure scenario: with the node powered off, an attacker has to brute-force the passphrase against the
 * Argon2id cost. While the node runs unlocked, the key is in RAM; {@link #close()} zeroes it.
 */
public final class Keyring implements AutoCloseable {

    /** Argon2id cost. Defaults: 64 MiB, 3 passes, 1 lane (about 0.2 s on a laptop). */
    public record Params(int memoryKiB, int iterations, int parallelism) {
        public static final Params DEFAULT = new Params(64 * 1024, 3, 1);

        public Params {
            if (memoryKiB < 8 || memoryKiB > (1 << 22) || iterations < 1 || iterations > 64
                    || parallelism < 1 || parallelism > 16) {
                throw new IllegalArgumentException("invalid Argon2 parameters");
            }
        }
    }

    private static final int MAGIC = 0x4e52594b; // "NRYK"
    private static final int VERSION = 1;
    private static final int SALT_SIZE = 16;
    private static final byte[] VERIFIER_PLAINTEXT = "noray-keyring-ok".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VERIFIER_AAD = "noray/keyring/verifier".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] master;
    private volatile boolean closed;

    private Keyring(byte[] master) {
        this.master = master;
    }

    public static boolean exists(Path file) {
        return Files.isRegularFile(file);
    }

    /**
     * Unlocks the keyring stored in {@code file}, or creates it (with {@code params}) if it does not exist yet.
     *
     * @throws FsException DENIED if the passphrase is wrong
     */
    public static Keyring openOrCreate(Path file, char[] passphrase, Params params) throws FsException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(passphrase, "passphrase");
        if (passphrase.length == 0) {
            throw new FsException(FsErrorCode.DENIED, "empty passphrase");
        }
        try {
            return exists(file) ? unlock(file, passphrase) : create(file, passphrase, params);
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "cannot access keyring " + file, e);
        }
    }

    private static Keyring create(Path file, char[] passphrase, Params params) throws IOException {
        byte[] salt = new byte[SALT_SIZE];
        RANDOM.nextBytes(salt);
        byte[] master = stretch(passphrase, salt, params);
        byte[] verifier = DataCrypto.sealRandomNonce(DataCrypto.derive(master, "noray/keyring/verify"),
                VERIFIER_AAD, VERIFIER_PLAINTEXT);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(MAGIC);
        out.writeByte(VERSION);
        out.writeInt(params.memoryKiB());
        out.writeInt(params.iterations());
        out.writeInt(params.parallelism());
        out.write(salt);
        out.writeShort(verifier.length);
        out.write(verifier);
        out.flush();

        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        SecureFiles.createOwnerOnly(tmp);
        Files.write(tmp, baos.toByteArray());
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file);
        }
        return new Keyring(master);
    }

    private static Keyring unlock(Path file, char[] passphrase) throws IOException {
        Params params;
        byte[] salt = new byte[SALT_SIZE];
        byte[] verifier;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != MAGIC) {
                throw new FsException(FsErrorCode.INTERNAL, "not a keyring file: " + file);
            }
            int version = in.readUnsignedByte();
            if (version != VERSION) {
                throw new FsException(FsErrorCode.INTERNAL, "unsupported keyring version " + version);
            }
            try {
                params = new Params(in.readInt(), in.readInt(), in.readInt());
            } catch (IllegalArgumentException e) {
                throw new FsException(FsErrorCode.INTERNAL, "corrupt keyring parameters", e);
            }
            in.readFully(salt);
            verifier = new byte[in.readUnsignedShort()];
            in.readFully(verifier);
        }
        byte[] master = stretch(passphrase, salt, params);
        try {
            byte[] check = DataCrypto.openRandomNonce(DataCrypto.derive(master, "noray/keyring/verify"),
                    VERIFIER_AAD, verifier);
            if (!Arrays.equals(check, VERIFIER_PLAINTEXT)) {
                throw new FsException(FsErrorCode.DENIED, "wrong passphrase");
            }
        } catch (FsException e) {
            Arrays.fill(master, (byte) 0);
            if (e.code() == FsErrorCode.DENIED) {
                throw e;
            }
            throw new FsException(FsErrorCode.DENIED, "wrong passphrase", e);
        }
        return new Keyring(master);
    }

    public static byte[] stretch(char[] passphrase, byte[] salt, Params params) {
        byte[] pw = Normalizer.normalize(new String(passphrase), Normalizer.Form.NFKC).getBytes(StandardCharsets.UTF_8);
        try {
            Argon2Parameters p = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(params.memoryKiB())
                    .withIterations(params.iterations())
                    .withParallelism(params.parallelism())
                    .withSalt(salt)
                    .build();
            Argon2BytesGenerator gen = new Argon2BytesGenerator();
            gen.init(p);
            byte[] out = new byte[DataCrypto.KEY_SIZE];
            gen.generateBytes(pw, out);
            return out;
        } finally {
            Arrays.fill(pw, (byte) 0);
        }
    }

    /** A 32-byte key bound to {@code purpose} (for example {@code "namespace-journal"}). */
    public byte[] subKey(String purpose) {
        if (closed) {
            throw new IllegalStateException("keyring is closed");
        }
        return DataCrypto.derive(master, "noray/subkey/v1|" + purpose);
    }

    public boolean isOpen() {
        return !closed;
    }

    /** Zeroes the master key. Sub-keys already handed out stay valid until their holders drop them. */
    @Override
    public void close() {
        closed = true;
        Arrays.fill(master, (byte) 0);
    }
}
