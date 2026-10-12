package com.redactado.noray.identity;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.identity.log.HeadPin;
import com.redactado.noray.identity.log.SignedLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SignedLogTest {

    private final TestClock clock = new TestClock();
    private final SigningKey owner = SigningKey.generate();

    private static void assertDenied(org.junit.jupiter.api.function.Executable e) {
        FsException ex = assertThrows(FsException.class, e);
        assertEquals(FsErrorCode.DENIED, ex.code(), ex.getMessage());
    }

    private SignedLog sample() {
        SignedLog log = SignedLog.create(owner, "docs", clock);
        for (int i = 0; i < 5; i++) {
            log.append(owner, ("v" + i).getBytes(), clock);
        }
        return log;
    }

    @Test
    void roundTripVerifiesTheWholeChain() {
        SignedLog log = sample();
        SignedLog back = SignedLog.decode(log.encode());
        assertEquals(6, back.size());
        assertEquals("docs", back.name());
        assertArrayEquals(log.head().hash(), back.head().hash());
        assertArrayEquals(owner.publicKey(), back.owner());
    }

    @Test
    void onlyWritersAppendAndOnlyTheOwnerAdmitsThem() {
        SignedLog log = SignedLog.create(owner, "docs", clock);
        SigningKey writer = SigningKey.generate();
        SigningKey intruder = SigningKey.generate();

        assertDenied(() -> log.append(writer, "x".getBytes(), clock));
        log.grant(owner, writer.publicKey(), clock);
        log.append(writer, "ok".getBytes(), clock);

        assertDenied(() -> log.grant(writer, intruder.publicKey(), clock)); // a writer cannot make writers
        assertDenied(() -> log.append(intruder, "x".getBytes(), clock));
        assertDenied(() -> log.revokeWriter(owner, owner.publicKey(), clock)); // the owner stays

        log.revokeWriter(owner, writer.publicKey(), clock);
        assertDenied(() -> log.append(writer, "late".getBytes(), clock));
        assertEquals(4, log.size(), "refused entries leave no trace");

        SignedLog back = SignedLog.decode(log.encode());
        assertTrue(back.isWriter(owner.publicKey()));
        assertFalse(back.isWriter(writer.publicKey()));
    }

    @Test
    void editingOrCuttingBytesBreaksTheLog() {
        byte[] bytes = sample().encode();

        byte[] edited = bytes.clone();
        edited[bytes.length / 2] ^= 1;
        assertDenied(() -> SignedLog.decode(edited));

        assertDenied(() -> SignedLog.decode(Arrays.copyOf(bytes, bytes.length - 3)));
        assertDenied(() -> SignedLog.decode(new byte[]{1, 2, 3}));
    }

    @Test
    void aWriterWhoseKeyWasStolenCannotRewriteHistoryOrBecomeOwner() {
        SignedLog log = SignedLog.create(owner, "docs", clock);
        SigningKey writer = SigningKey.generate();
        log.grant(owner, writer.publicKey(), clock);
        log.append(owner, "legit-1".getBytes(), clock);
        log.append(owner, "legit-2".getBytes(), clock);

        // the thief builds a log with the same name and tries to pass it off as the same history
        SignedLog thief = SignedLog.create(writer, "docs", clock);
        thief.append(writer, "evil".getBytes(), clock);
        assertFalse(Arrays.equals(thief.owner(), log.owner()));
        assertThrows(FsException.class, () -> log.adopt(thief));
        assertEquals(4, log.size());
    }

    @Test
    void divergentCopiesAreRecognisedAsAFork() {
        SignedLog a = sample();
        SignedLog b = SignedLog.decode(a.encode());
        a.append(owner, "left".getBytes(), clock);
        clock.advance(java.time.Duration.ofSeconds(1));
        b.append(owner, "right".getBytes(), clock);

        SignedLog.Fork fork = SignedLog.findFork(a, b).orElseThrow();
        assertEquals(6, fork.seq());
        assertDenied(() -> a.adopt(b));
        assertDenied(() -> b.adopt(a));
    }

    @Test
    void adoptingAnExtensionCatchesUp() {
        SignedLog master = sample();
        SignedLog replica = SignedLog.decode(master.encode());
        master.append(owner, "new-1".getBytes(), clock);
        master.append(owner, "new-2".getBytes(), clock);

        assertTrue(replica.adopt(master));
        assertEquals(master.size(), replica.size());
        assertFalse(replica.adopt(master), "nothing new");
    }

    @Test
    void aPinExposesRollbackAndRewriting(@TempDir Path dir) {
        SignedLog log = sample();
        HeadPin pin = log.pin();
        pin.check(log);

        // someone serves an older prefix of the very same log
        SignedLog full = log;
        SignedLog prefix = SignedLog.create(owner, "docs", clock); // same owner, shorter and different history
        assertDenied(() -> pin.check(prefix));

        SignedLog older = SignedLog.decode(truncated(full, 3));
        assertDenied(() -> pin.check(older));

        // pins persist
        Path file = dir.resolve("pin");
        pin.save(file);
        HeadPin loaded = HeadPin.load(file).orElseThrow();
        assertEquals(pin.seq(), loaded.seq());
        assertArrayEquals(pin.hash(), loaded.hash());
        loaded.check(log);
        assertTrue(HeadPin.load(dir.resolve("missing")).isEmpty());

        // a longer log that still contains the pinned entry is fine
        log.append(owner, "more".getBytes(), clock);
        pin.check(log);
        assertTrue(log.pin().isNewerThan(pin));
    }

    @Test
    void aCorruptPinFileIsRefusedNotIgnored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pin");
        Files.write(file, new byte[7]);
        assertDenied(() -> HeadPin.load(file));
    }

    /** Re-encodes the first {@code count} entries of a log (what a store that drops the tail would serve). */
    private static byte[] truncated(SignedLog log, int count) {
        byte[] full = log.encode();
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(full);
        b.getInt();
        b.getInt();
        int end = 8;
        for (int i = 0; i < count; i++) {
            int len = b.getInt(end);
            end += 4 + len + 64;
        }
        byte[] out = Arrays.copyOf(full, end);
        java.nio.ByteBuffer.wrap(out).putInt(4, count);
        return out;
    }

    @Test
    void entriesTravelInBatchesAndOnlyContinueTheLogTheyBelongTo() {
        SignedLog log = sample();
        SignedLog copy = SignedLog.fromEntries(SignedLog.decodeEntries(SignedLog.encodeEntries(log.entriesFrom(0, 3))));
        assertEquals(3, copy.size());
        assertEquals(3, copy.extend(SignedLog.decodeEntries(SignedLog.encodeEntries(log.entriesFrom(3, 10)))));
        assertArrayEquals(log.head().hash(), copy.head().hash());
        assertEquals(0, copy.extend(log.entriesFrom(0, 10)), "what is already there is skipped");

        // a gap, a fork and a log without genesis are refused
        SignedLog shorter = SignedLog.fromEntries(log.entriesFrom(0, 2));
        assertDenied(() -> shorter.extend(log.entriesFrom(4, 2)));
        SignedLog other = SignedLog.create(owner, "docs", clock);
        other.append(owner, "different".getBytes(), clock);
        assertDenied(() -> shorter.extend(other.entriesFrom(1, 1)));
        assertDenied(() -> SignedLog.fromEntries(log.entriesFrom(1, 3)));
        assertTrue(log.entriesFrom(99, 5).isEmpty());
    }
}
