package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.NodeType;
import com.redactado.noray.filesystem.vfs.journal.NamespaceRecord;
import com.redactado.noray.filesystem.vfs.journal.NamespaceRecordCodec;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Malformed namespace records fail with IllegalArgumentException and nothing else (no underflow, no huge allocation). */
class JournalCodecFuzzTest {

    @Test
    void mutatedRecordsFailOnlyAsIllegalArgument() {
        byte[] good = NamespaceRecordCodec.encode(List.of(
                new NamespaceRecord.Create("/mnt/data/a", NodeType.FILE, 7, 1),
                new NamespaceRecord.SetData("/mnt/data/a", "ab".repeat(32), 42),
                new NamespaceRecord.SetAttr("/mnt/data", "quota.bytes", "1000"),
                new NamespaceRecord.Unlink("/mnt/data/b")));
        assertEquals(4, NamespaceRecordCodec.decode(good).size());
        Random rnd = new Random(Long.getLong("noray.fuzz.seed", 13L));
        int rounds = Integer.getInteger("noray.fuzz.rounds", 20_000);
        for (int i = 0; i < rounds; i++) {
            byte[] b = good.clone();
            for (int e = 0, n = 1 + rnd.nextInt(4); e < n; e++) {
                switch (rnd.nextInt(3)) {
                    case 0 -> b[rnd.nextInt(b.length)] = (byte) rnd.nextInt(256);
                    case 1 -> b[rnd.nextInt(b.length)] = (byte) (rnd.nextBoolean() ? 0x7f : 0xff); // big lengths
                    default -> b = Arrays.copyOf(b, rnd.nextInt(b.length + 1));
                }
                if (b.length == 0) break;
            }
            try {
                NamespaceRecordCodec.decode(b);
            } catch (IllegalArgumentException expected) {
                // the only acceptable failure
            }
        }
    }
}
