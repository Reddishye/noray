package com.redactado.noray.net.overlay;

import com.redactado.noray.data.crypto.EncryptedFile;
import com.redactado.noray.data.crypto.Keyring;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.util.SecureFiles;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Static X25519 + ML-KEM-768 keys. NodeId remains data/net/local.id. */
public final class StaticKeys {

    private static final HexFormat HEX = HexFormat.of();

    public final NodeId nodeId;
    public final byte[] x25519Priv;
    public final byte[] x25519Pub;
    public final MLKEMPrivateKeyParameters mlkemPriv;
    public final MLKEMPublicKeyParameters mlkemPub;

    public StaticKeys(
            NodeId nodeId,
            byte[] x25519Priv,
            byte[] x25519Pub,
            MLKEMPrivateKeyParameters mlkemPriv,
            MLKEMPublicKeyParameters mlkemPub
    ) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.x25519Priv = x25519Priv;
        this.x25519Pub = x25519Pub;
        this.mlkemPriv = mlkemPriv;
        this.mlkemPub = mlkemPub;
    }

    public String x25519PubHex() {
        return HEX.formatHex(x25519Pub);
    }

    public String mlkemPubHex() {
        return HEX.formatHex(mlkemPub.getEncoded());
    }

    private static final String SEAL_LABEL = "noray/static-keys/v1";

    /**
     * With a keyring the private keys are stored sealed ({@code net/local.keys.sealed}); a plain file from before is
     * sealed and overwritten on the first start with one. Without a keyring they stay in the 0600 plain file.
     */
    public static StaticKeys loadOrCreate(Path dataDir, NodeId nodeId, Keyring keyring) {
        if (keyring == null) {
            if (Files.exists(dataDir.resolve("net").resolve("local.keys.sealed"))) {
                throw new IllegalStateException("the network keys are sealed but no keyring is configured "
                        + "(noray.keyring.passphrase.file or NORAY_PASSPHRASE)");
            }
            return loadOrCreate(dataDir, nodeId);
        }
        Path plain = dataDir.resolve("net").resolve("local.keys");
        Path sealed = dataDir.resolve("net").resolve("local.keys.sealed");
        byte[] key = keyring.subKey("static-keys");
        try {
            StaticKeys keys;
            if (Files.isRegularFile(sealed)) {
                byte[] body = EncryptedFile.read(sealed, key, SEAL_LABEL).orElseThrow();
                keys = parse(new String(body, StandardCharsets.UTF_8), nodeId);
                Arrays.fill(body, (byte) 0);
            } else if (Files.isRegularFile(plain)) {
                keys = read(plain, nodeId);
                EncryptedFile.write(sealed, key, SEAL_LABEL, body(keys).getBytes(StandardCharsets.UTF_8));
            } else {
                keys = loadOrCreateFresh(nodeId);
                EncryptedFile.write(sealed, key, SEAL_LABEL, body(keys).getBytes(StandardCharsets.UTF_8));
            }
            if (Files.exists(plain)) {
                shred(plain);
            }
            return keys;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load/create the sealed network keys", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static void shred(Path file) throws IOException {
        long size = Files.size(file);
        Files.write(file, new byte[(int) Math.min(size, 1 << 20)]); // best effort: a journaling disk may keep the old blocks
        Files.delete(file);
    }

    private static StaticKeys loadOrCreateFresh(NodeId nodeId) {
        byte[] xPriv = NoiseCrypto.x25519GeneratePrivate();
        byte[] xPub = NoiseCrypto.x25519Public(xPriv);
        MLKEMKeyPairGenerator g = new MLKEMKeyPairGenerator();
        g.init(new MLKEMKeyGenerationParameters(new SecureRandom(), MLKEMParameters.ml_kem_768));
        AsymmetricCipherKeyPair kp = g.generateKeyPair();
        return new StaticKeys(nodeId, xPriv, xPub, (MLKEMPrivateKeyParameters) kp.getPrivate(),
                (MLKEMPublicKeyParameters) kp.getPublic());
    }

    public static StaticKeys loadOrCreate(Path dataDir, NodeId nodeId) {
        Path file = dataDir.resolve("net").resolve("local.keys");
        try {
            if (Files.isRegularFile(file)) {
                // Tighten permissions of key files created by older versions.
                SecureFiles.restrictToOwner(file);
                return read(file, nodeId);
            }
            Files.createDirectories(file.getParent());
            StaticKeys keys = loadOrCreateFresh(nodeId);
            write(file, keys);
            return keys;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load/create local.keys", e);
        }
    }

    private static String body(StaticKeys keys) {
        return "nodeId=" + keys.nodeId.hex() + "\n"
                + "x25519priv=" + HEX.formatHex(keys.x25519Priv) + "\n"
                + "x25519pub=" + keys.x25519PubHex() + "\n"
                + "mlkem768priv=" + HEX.formatHex(keys.mlkemPriv.getEncoded()) + "\n"
                + "mlkem768pub=" + keys.mlkemPubHex() + "\n";
    }

    private static void write(Path file, StaticKeys keys) throws IOException {
        String body = body(keys);
        Path tmp = file.resolveSibling("local.keys.tmp");
        Files.deleteIfExists(tmp);
        SecureFiles.createOwnerOnly(tmp);
        Files.writeString(tmp, body, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static StaticKeys read(Path file, NodeId nodeId) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8), nodeId);
    }

    private static StaticKeys parse(String text, NodeId nodeId) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        byte[] xPriv = HEX.parseHex(map.get("x25519priv"));
        byte[] xPub = HEX.parseHex(map.get("x25519pub"));
        byte[] kemPriv = HEX.parseHex(map.get("mlkem768priv"));
        byte[] kemPub = HEX.parseHex(map.get("mlkem768pub"));
        MLKEMPublicKeyParameters pub = new MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, kemPub);
        MLKEMPrivateKeyParameters priv = new MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, kemPriv);
        return new StaticKeys(nodeId, xPriv, xPub, priv, pub);
    }
}
