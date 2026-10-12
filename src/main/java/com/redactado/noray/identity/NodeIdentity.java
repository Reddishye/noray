package com.redactado.noray.identity;

import com.redactado.noray.data.crypto.EncryptedFile;
import com.redactado.noray.data.crypto.Keyring;
import com.redactado.noray.util.SecureFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * This node's own signing key. It is generated on the node and never leaves it: nobody else (and no other node)
 * ever holds a copy, so compromising another node reveals nothing about this one. With a keyring the file is
 * sealed under a key derived from the passphrase; without one it is only protected by file permissions.
 */
public final class NodeIdentity {

    private static final String LABEL = "noray/node-identity/v1";
    private static final byte[] PLAIN_MAGIC = {'N', 'R', 'Y', 'I'};

    private final SigningKey key;
    private final boolean sealed;

    private NodeIdentity(SigningKey key, boolean sealed) {
        this.key = key;
        this.sealed = sealed;
    }

    public static NodeIdentity loadOrCreate(Path dataDir, Keyring keyring) {
        Path file = dataDir.resolve("identity").resolve("node.key");
        try {
            if (Files.isRegularFile(file)) {
                byte[] raw = Files.readAllBytes(file);
                boolean plain = raw.length == PLAIN_MAGIC.length + 32 && startsWithMagic(raw);
                byte[] seed;
                if (plain) {
                    seed = Arrays.copyOfRange(raw, PLAIN_MAGIC.length, raw.length);
                } else {
                    if (keyring == null) {
                        throw new IllegalStateException("node identity " + file + " is sealed but no keyring is open");
                    }
                    seed = EncryptedFile.read(file, keyring.subKey("node-identity"), LABEL).orElseThrow();
                }
                SigningKey k = SigningKey.fromSeed(seed);
                Arrays.fill(seed, (byte) 0);
                if (plain && keyring != null) {
                    write(file, k.seed(), keyring); // a keyring appeared: seal the key now
                    return new NodeIdentity(k, true);
                }
                SecureFiles.restrictToOwner(file);
                return new NodeIdentity(k, !plain);
            }
            SigningKey k = SigningKey.generate();
            write(file, k.seed(), keyring);
            return new NodeIdentity(k, keyring != null);
        } catch (IOException e) {
            throw new IllegalStateException("cannot load or create the node identity", e);
        }
    }

    private static boolean startsWithMagic(byte[] raw) {
        for (int i = 0; i < PLAIN_MAGIC.length; i++) {
            if (raw[i] != PLAIN_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static void write(Path file, byte[] seed, Keyring keyring) throws IOException {
        if (keyring != null) {
            EncryptedFile.write(file, keyring.subKey("node-identity"), LABEL, seed);
            return;
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("node.key.tmp");
        Files.deleteIfExists(tmp);
        SecureFiles.createOwnerOnly(tmp);
        byte[] raw = new byte[PLAIN_MAGIC.length + seed.length];
        System.arraycopy(PLAIN_MAGIC, 0, raw, 0, PLAIN_MAGIC.length);
        System.arraycopy(seed, 0, raw, PLAIN_MAGIC.length, seed.length);
        Files.write(tmp, raw);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public SigningKey signingKey() {
        return key;
    }

    public byte[] publicKey() {
        return key.publicKey();
    }

    /** Whether the key is stored encrypted (a keyring was available). */
    public boolean sealed() {
        return sealed;
    }
}
