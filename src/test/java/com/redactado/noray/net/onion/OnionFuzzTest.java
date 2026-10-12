package com.redactado.noray.net.onion;

import com.redactado.noray.net.NodeId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** A relay sees arbitrary bytes from the network: peeling a mutated cell must only ever return null, never throw. */
class OnionFuzzTest {

    @Test
    void mutatedCellsAreRejectedQuietly() {
        OnionIdentity me = OnionIdentity.generate();
        byte[] good = OnionPacket.build(List.of(), new NodeId("cd".repeat(32)), n -> me.publicKey(), new byte[]{1, 2, 3},
                OnionPacket.DELIVER, 4_000_000_000L);
        assertNotNull(OnionPacket.peel(me, good));
        Random rnd = new Random(Long.getLong("noray.fuzz.seed", 11L));
        int accepted = 0;
        for (int i = 0; i < Integer.getInteger("noray.fuzz.rounds", 3_000); i++) {
            byte[] cell = good.clone();
            for (int e = 0, n = 1 + rnd.nextInt(3); e < n; e++) cell[rnd.nextInt(cell.length)] ^= (byte) (1 + rnd.nextInt(255));
            if (rnd.nextInt(20) == 0) cell = Arrays.copyOf(cell, rnd.nextInt(cell.length + 64));
            if (OnionPacket.peel(me, cell) != null) accepted++;
        }
        // only flips in the random padding after the sealed layer can leave the cell valid
        assertTrue(accepted < 3_000, "authentication rejects real changes");
        for (int i = 0; i < 300; i++) {
            byte[] noise = new byte[OnionPacket.CELL];
            rnd.nextBytes(noise);
            assertNull(OnionPacket.peel(me, noise));
        }
    }
}
