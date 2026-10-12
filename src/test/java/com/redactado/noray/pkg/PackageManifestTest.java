package com.redactado.noray.pkg;

import com.redactado.noray.identity.SigningKey;
import com.redactado.noray.pkg.PackageException.Reason;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static com.redactado.noray.pkg.TestPackages.utf8;
import static org.junit.jupiter.api.Assertions.*;

/** The manifest parser refuses everything that is not exactly the signed canonical text. */
class PackageManifestTest {

    private static final String SHA = "a".repeat(64);
    private static final String VALID = """
            noray-package=1
            id=com.example.tool
            version=1.2.0
            noray.api=1
            sandbox=process
            main=com.example.tool.Main
            payload.sha256=%s
            payload.size=10
            publisher=example
            requests=fs.read:/mnt/a,fs.write:/mnt/a/out
            description=A tool
            """.formatted(SHA);

    private static Reason reason(String text) {
        return reason(utf8(text));
    }

    private static Reason reason(byte[] bytes) {
        return assertThrows(PackageException.class, () -> PackageManifest.parse(bytes)).reason();
    }

    @Test
    void aCanonicalManifestParses() {
        PackageManifest m = PackageManifest.parse(utf8(VALID));
        assertEquals("com.example.tool", m.id());
        assertEquals(Version.parse("1.2.0"), m.version());
        assertEquals(PackageManifest.Sandboxing.PROCESS, m.sandbox());
        assertEquals(List.of("fs.read:/mnt/a", "fs.write:/mnt/a/out"), m.requests());
        assertEquals(10, m.payloadSize());
        assertTrue(m.signatures().isEmpty());
        assertArrayEquals(utf8(VALID), m.body());
    }

    @Test
    void structuralViolationsAreRefused() {
        assertEquals(Reason.MALFORMED, reason(VALID.replace("\n", "\r\n")), "CRLF");
        assertEquals(Reason.MALFORMED, reason(VALID.substring(0, VALID.length() - 1)), "no final newline");
        assertEquals(Reason.MALFORMED, reason(VALID.replace("noray-package=1", "noray-package=2")), "magic");
        assertEquals(Reason.MALFORMED, reason(VALID + "id=com.example.other\n"), "duplicate key");
        assertEquals(Reason.MALFORMED, reason(VALID + "postinstall=rm -rf /\n"), "unknown key");
        assertEquals(Reason.MALFORMED, reason(VALID.replace("publisher=example\n", "")), "missing key");
        assertEquals(Reason.MALFORMED, reason(VALID.replace("noray.api=1\n", "noray.api=1\n\n")), "blank line");
        assertEquals(Reason.MALFORMED, reason(VALID.replace("A tool", "A ‮loot")), "bidi override");
        assertEquals(Reason.MALFORMED, reason(VALID.replace("A tool", "A\u0007tool")), "control character");
        assertEquals(Reason.MALFORMED, reason(new byte[]{'n', (byte) 0xC3, (byte) 0x28, '\n'}), "invalid UTF-8");
        assertEquals(Reason.MALFORMED, reason(new byte[PackageManifest.MAX_BYTES + 1]), "too large");
        assertEquals(Reason.MALFORMED, reason(VALID + "signature=" + "b".repeat(64) + ":" + "c".repeat(128)
                + "\ndescription=late\n"), "line after the signatures");
        String sig = "signature=" + "b".repeat(64) + ":" + "c".repeat(128) + "\n";
        assertEquals(Reason.MALFORMED, reason(VALID + sig + sig), "two signatures by one key");
    }

    @Test
    void fieldValuesAreValidated() {
        assertEquals(Reason.MALFORMED, reason(VALID.replace("id=com.example.tool", "id=../../etc")));
        assertEquals(Reason.MALFORMED, reason(VALID.replace("id=com.example.tool", "id=com.redactado.noray.evil")), "core namespace");
        assertEquals(Reason.MALFORMED, reason(VALID.replace("version=1.2.0", "version=1.2")));
        assertEquals(Reason.MALFORMED, reason(VALID.replace("version=1.2.0", "version=01.2.0")));
        assertEquals(Reason.MALFORMED, reason(VALID.replace("sandbox=process", "sandbox=none")));
        assertEquals(Reason.MALFORMED, reason(VALID.replace("payload.size=10", "payload.size=-1")));
        assertEquals(Reason.MALFORMED, reason(VALID.replace("payload.size=10", "payload.size=0x10")));
        assertEquals(Reason.MALFORMED, reason(VALID.replace(SHA, SHA.toUpperCase())));
        assertEquals(Reason.MALFORMED, reason(VALID.replace("fs.read:/mnt/a,", "fs.read:relative,")));
        assertEquals(Reason.MALFORMED, reason(VALID.replace("fs.read:/mnt/a,", "fs.read:/mnt/a, ")), "stray space");
        assertEquals(Reason.MALFORMED, reason(VALID.replace("fs.read:/mnt/a,", "fs.write:/mnt/a/out,")), "duplicate request");
        assertEquals(Reason.MALFORMED, reason(VALID.replace("main=com.example.tool.Main", "main=java.lang.Runtime;exec")));
    }

    @Test
    void signaturesCoverEveryLineBeforeThem() {
        SigningKey k = SigningKey.generate();
        byte[] signed = TestPackages.sign(utf8(VALID), k);
        PackageManifest m = PackageManifest.parse(signed);
        assertEquals(1, m.signatures().size());
        assertArrayEquals(utf8(VALID), m.body());
        Publisher p = TestPackages.publisher("example", false, 1, k);
        assertEquals(1, p.validSignatures(m));
        // change one character of the body: still parses, no longer verifies
        PackageManifest tampered = PackageManifest.parse(new String(signed, StandardCharsets.UTF_8)
                .replace("fs.write:/mnt/a/out", "fs.write:/mnt/a/ou/").getBytes(StandardCharsets.UTF_8));
        assertEquals(0, p.validSignatures(tampered));
        // re-signing by the same key replaces the old signature instead of adding one
        assertEquals(1, PackageManifest.parse(TestPackages.sign(signed, k)).signatures().size());
    }

    @Test
    void randomMutationsOnlyEverFailAsMalformedOrParse() {
        byte[] base = TestPackages.sign(utf8(VALID), SigningKey.generate());
        Random rnd = new Random(Long.getLong("noray.fuzz.seed", 7L));
        int parsed = 0;
        for (int i = 0; i < 20_000; i++) {
            byte[] b = base.clone();
            int edits = 1 + rnd.nextInt(4);
            for (int e = 0; e < edits; e++) {
                int at = rnd.nextInt(b.length);
                switch (rnd.nextInt(3)) {
                    case 0 -> b[at] = (byte) rnd.nextInt(256);
                    case 1 -> b[at] = (byte) "=\n:,.@-0aZ".charAt(rnd.nextInt(10));
                    default -> b = Arrays.copyOf(b, Math.max(1, at)); // truncate
                }
            }
            try {
                PackageManifest.parse(b);
                parsed++;
            } catch (PackageException expected) {
                assertEquals(Reason.MALFORMED, expected.reason());
            }
            // anything else (NPE, IndexOutOfBounds, NumberFormatException...) fails the test
        }
        assertTrue(parsed < 20_000);
    }

    @Test
    void archivesAreStrict() {
        PackageArchive a = new PackageArchive(utf8(VALID), new byte[10]);
        byte[] bytes = a.toBytes();
        assertArrayEquals(a.payload(), PackageArchive.read(bytes).payload());
        assertEquals(Reason.MALFORMED, assertThrows(PackageException.class,
                () -> PackageArchive.read(Arrays.copyOf(bytes, bytes.length + 1))).reason(), "trailing byte");
        assertEquals(Reason.MALFORMED, assertThrows(PackageException.class,
                () -> PackageArchive.read(Arrays.copyOf(bytes, bytes.length - 1))).reason(), "truncated");
        byte[] bad = bytes.clone();
        bad[0] = 'X';
        assertEquals(Reason.MALFORMED, assertThrows(PackageException.class, () -> PackageArchive.read(bad)).reason(), "magic");
        byte[] huge = bytes.clone();
        huge[7] = (byte) 0x7f; // manifest length far above the limit: refused before allocating
        assertEquals(Reason.MALFORMED, assertThrows(PackageException.class, () -> PackageArchive.read(huge)).reason());
    }

    @Test
    void versionsFollowSemverPrecedence() {
        List<String> ordered = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
                "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.10.0", "2.0.0");
        for (int i = 1; i < ordered.size(); i++) {
            assertTrue(Version.parse(ordered.get(i - 1)).compareTo(Version.parse(ordered.get(i))) < 0,
                    ordered.get(i - 1) + " < " + ordered.get(i));
        }
        assertEquals("1.0.0-rc.1", Version.parse("1.0.0-rc.1").toString());
    }
}
