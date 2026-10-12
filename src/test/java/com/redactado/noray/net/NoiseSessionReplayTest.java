package com.redactado.noray.net;

import com.redactado.noray.net.overlay.NoiseSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NoiseSessionReplayTest {

    private static final byte[] AD = {1, 2, 3};
    private NoiseSession sender;
    private NoiseSession receiver;

    @BeforeEach
    void setUp() {
        byte[] k1 = new byte[32];
        byte[] k2 = new byte[32];
        java.util.Arrays.fill(k1, (byte) 1);
        java.util.Arrays.fill(k2, (byte) 2);
        byte[] h = new byte[32];
        sender = new NoiseSession(k1, k2, h);
        receiver = new NoiseSession(k2, k1, h);
    }

    private byte[][] encryptMany(int n) {
        byte[][] out = new byte[n][];
        for (int i = 0; i < n; i++) {
            out[i] = sender.encrypt(AD, new byte[] {(byte) i});
        }
        return out;
    }

    @Test
    void duplicateNonceIsRejected() {
        byte[][] ct = encryptMany(3);
        assertArrayEquals(new byte[] {0}, receiver.decrypt(0, AD, ct[0]));
        assertThrows(IllegalStateException.class, () -> receiver.decrypt(0, AD, ct[0]));
        assertArrayEquals(new byte[] {1}, receiver.decrypt(1, AD, ct[1]));
    }

    @Test
    void outOfOrderWithinWindowIsAcceptedOnce() {
        byte[][] ct = encryptMany(10);
        receiver.decrypt(9, AD, ct[9]);
        assertDoesNotThrow(() -> receiver.decrypt(3, AD, ct[3]));
        assertThrows(IllegalStateException.class, () -> receiver.decrypt(3, AD, ct[3]));
        assertThrows(IllegalStateException.class, () -> receiver.decrypt(9, AD, ct[9]));
    }

    @Test
    void nonceOlderThanWindowIsRejectedEvenAfterManyPackets() {
        byte[][] ct = encryptMany(5000);
        // Old behaviour: a 1024-entry ring forgot nonce 0 after enough traffic, allowing its replay.
        for (int i = 0; i < 5000; i++) {
            receiver.decrypt(i, AD, ct[i]);
        }
        assertThrows(IllegalStateException.class, () -> receiver.decrypt(0, AD, ct[0]));
        assertThrows(IllegalStateException.class, () -> receiver.decrypt(2000, AD, ct[2000]));
        assertThrows(IllegalStateException.class, () -> receiver.decrypt(4999, AD, ct[4999]));
    }

    @Test
    void largeForwardJumpResetsWindowWithoutAllowingReplays() {
        byte[][] ct = encryptMany(10000);
        receiver.decrypt(5, AD, ct[5]);
        receiver.decrypt(9000, AD, ct[9000]);
        assertThrows(IllegalStateException.class, () -> receiver.decrypt(5, AD, ct[5]));
        assertThrows(IllegalStateException.class, () -> receiver.decrypt(9000, AD, ct[9000]));
        assertDoesNotThrow(() -> receiver.decrypt(8999, AD, ct[8999]));
    }

    @Test
    void forgedPacketDoesNotAdvanceWindow() {
        byte[][] ct = encryptMany(3);
        byte[] forged = ct[2].clone();
        forged[0] ^= 0x55;
        assertThrows(RuntimeException.class, () -> receiver.decrypt(100000, AD, forged));
        // legitimate low nonces must still be accepted
        assertDoesNotThrow(() -> receiver.decrypt(0, AD, ct[0]));
    }
}
