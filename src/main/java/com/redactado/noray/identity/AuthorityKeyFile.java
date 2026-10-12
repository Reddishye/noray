package com.redactado.noray.identity;

import com.redactado.noray.data.crypto.DataCrypto;
import com.redactado.noray.data.crypto.Keyring;
import com.redactado.noray.util.SecureFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * The file an authority key lives in. A key that can admit nodes should not sit on a disk in the clear, so the default
 * is a passphrase-protected file ({@code noray-authority-key-v1 salt memoryKiB iterations sealedSeed}, Argon2id and
 * AEAD); a plain hex seed is still read (and writable on request) for keys that are kept in a safe place by other
 * means.
 */
public final class AuthorityKeyFile {

    private static final String MAGIC = "noray-authority-key-v1";
    private static final byte[] AAD = "noray/authority-key/v1".getBytes(StandardCharsets.UTF_8);
    private static final HexFormat HEX = HexFormat.of();

    private AuthorityKeyFile() {
    }

    public static boolean isProtected(Path file) throws IOException {
        return Files.readString(file).trim().startsWith(MAGIC);
    }

    /** @param passphrase null writes the seed in the clear (mode 0600) */
    public static void write(Path file, SigningKey key, char[] passphrase) throws IOException {
        write(file, key, passphrase, Keyring.Params.DEFAULT);
    }

    public static void write(Path file, SigningKey key, char[] passphrase, Keyring.Params params) throws IOException {
        if (file.toAbsolutePath().getParent() != null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }
        String text;
        if (passphrase == null) {
            text = HEX.formatHex(key.seed());
        } else {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] k = Keyring.stretch(passphrase, salt, params);
            try {
                byte[] sealed = DataCrypto.sealRandomNonce(k, AAD, key.seed());
                text = MAGIC + " " + HEX.formatHex(salt) + " " + params.memoryKiB() + " " + params.iterations() + " "
                        + HEX.formatHex(sealed);
            } finally {
                Arrays.fill(k, (byte) 0);
            }
        }
        SecureFiles.createOwnerOnly(file);
        Files.writeString(file, text + "\n");
    }

    /** @throws IllegalArgumentException when the file is protected and the passphrase is missing or wrong */
    public static SigningKey read(Path file, char[] passphrase) throws IOException {
        String text = Files.readString(file).trim();
        if (!text.startsWith(MAGIC)) {
            return SigningKey.fromSeed(HEX.parseHex(text));
        }
        if (passphrase == null) {
            throw new IllegalArgumentException(file + " is protected by a passphrase: give --passphrase-file FILE "
                    + "or set NORAY_AUTHORITY_PASSPHRASE");
        }
        String[] p = text.split(" ");
        if (p.length != 5) {
            throw new IllegalArgumentException("malformed authority key file " + file);
        }
        Keyring.Params params = new Keyring.Params(Integer.parseInt(p[2]), Integer.parseInt(p[3]), 1);
        byte[] k = Keyring.stretch(passphrase, HEX.parseHex(p[1]), params);
        try {
            return SigningKey.fromSeed(DataCrypto.openRandomNonce(k, AAD, HEX.parseHex(p[4])));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("wrong passphrase for " + file);
        } finally {
            Arrays.fill(k, (byte) 0);
        }
    }
}
