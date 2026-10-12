package com.redactado.noray.net.overlay;

import com.redactado.noray.data.crypto.Keyring;
import com.redactado.noray.net.NodeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** The node's long-term network keys must not sit on disk in the clear when a keyring exists. */
class StaticKeysSealedTest {

    @TempDir
    Path dir;

    private static final NodeId ID = new NodeId("ab".repeat(32));
    private static final Keyring.Params CHEAP = new Keyring.Params(64, 1, 1);

    private Keyring keyring(String name, String passphrase) {
        return Keyring.openOrCreate(dir.resolve(name), passphrase.toCharArray(), CHEAP);
    }

    @Test
    void keysAreSealedAndComeBackIdentical() throws Exception {
        try (Keyring k = keyring("kr", "pass")) {
            StaticKeys first = StaticKeys.loadOrCreate(dir, ID, k);
            Path sealed = dir.resolve("net/local.keys.sealed");
            assertTrue(Files.isRegularFile(sealed));
            assertFalse(Files.exists(dir.resolve("net/local.keys")), "no plaintext copy");
            String onDisk = new String(Files.readAllBytes(sealed), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(onDisk.contains(first.x25519PubHex()) || onDisk.contains("x25519priv"));

            StaticKeys again = StaticKeys.loadOrCreate(dir, ID, k);
            assertArrayEquals(first.x25519Priv, again.x25519Priv);
            assertArrayEquals(first.mlkemPriv.getEncoded(), again.mlkemPriv.getEncoded());
        }
    }

    @Test
    void aPlaintextFileFromAnOlderVersionIsSealedAndRemoved() throws Exception {
        StaticKeys legacy = StaticKeys.loadOrCreate(dir, ID);
        assertTrue(Files.exists(dir.resolve("net/local.keys")));
        try (Keyring k = keyring("kr", "pass")) {
            StaticKeys sealed = StaticKeys.loadOrCreate(dir, ID, k);
            assertArrayEquals(legacy.x25519Priv, sealed.x25519Priv);
            assertFalse(Files.exists(dir.resolve("net/local.keys")));
            assertTrue(Files.exists(dir.resolve("net/local.keys.sealed")));
        }
    }

    @Test
    void anotherPassphraseCannotOpenThemAndNoKeyringIsRefusedRatherThanIgnored() {
        try (Keyring k = keyring("kr1", "right")) {
            StaticKeys.loadOrCreate(dir, ID, k);
        }
        try (Keyring other = keyring("kr2", "wrong")) {
            assertThrows(RuntimeException.class, () -> StaticKeys.loadOrCreate(dir, ID, other));
        }
        // silently creating fresh plaintext keys next to sealed ones would change the node's identity
        assertThrows(IllegalStateException.class, () -> StaticKeys.loadOrCreate(dir, ID, null));
        assertFalse(Files.exists(dir.resolve("net/local.keys")));
        assertTrue(Arrays.stream(dir.resolve("net").toFile().list()).noneMatch(n -> n.equals("local.keys")));
    }
}
