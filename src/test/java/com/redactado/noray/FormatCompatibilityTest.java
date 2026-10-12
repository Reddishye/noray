package com.redactado.noray;

import com.redactado.noray.data.crypto.Keyring;
import com.redactado.noray.filesystem.api.NodeType;
import com.redactado.noray.filesystem.vfs.journal.FileNamespaceJournal;
import com.redactado.noray.filesystem.vfs.journal.NamespaceRecord;
import com.redactado.noray.filesystem.vfs.journal.PayloadCipher;
import com.redactado.noray.identity.SigningKey;
import com.redactado.noray.ops.Backup;
import com.redactado.noray.pkg.PackageArchive;
import com.redactado.noray.pkg.PackageManifest;
import com.redactado.noray.pkg.PackageVerifier;
import com.redactado.noray.pkg.Publisher;
import com.redactado.noray.pkg.PublisherRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Upgrade safety: files written by release 1.0 (committed under src/test/resources/compat/1.0) must still open. A change
 * that breaks this needs a migration, not a new fixture. Regenerate only when adding a new release's fixtures:
 * {@code ./gradlew test --tests '*FormatCompatibilityTest' -Dnoray.compat.write=true}.
 */
class FormatCompatibilityTest {

    @TempDir
    Path tmp;

    private static final Path FIXTURES = Path.of("src/test/resources/compat/1.0");
    private static final char[] PASS = "compat-fixture".toCharArray();
    private static final Keyring.Params CHEAP = new Keyring.Params(64, 1, 1);
    private static final List<NamespaceRecord> RECORDS = List.of(
            new NamespaceRecord.Create("/mnt/data/a.txt", NodeType.FILE, 7, 1),
            new NamespaceRecord.SetData("/mnt/data/a.txt", "ab".repeat(32), 42),
            new NamespaceRecord.SetAttr("/mnt/data", "quota.bytes", "1000"));

    private static SigningKey publisherKey() {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 7);
        return SigningKey.fromSeed(seed);
    }

    @Test
    void writeFixturesWhenAsked() throws Exception {
        if (!Boolean.getBoolean("noray.compat.write")) return;
        Files.createDirectories(FIXTURES);
        Path keyringFile = FIXTURES.resolve("keyring");
        Files.deleteIfExists(keyringFile);
        try (Keyring k = Keyring.openOrCreate(keyringFile, PASS, CHEAP)) {
            Path journal = FIXTURES.resolve("namespace.journal");
            Files.deleteIfExists(journal);
            FileNamespaceJournal j = new FileNamespaceJournal(journal, true, PayloadCipher.aead(k.subKey("namespace-journal")));
            j.load(); // opens the (new, empty) journal
            for (NamespaceRecord r : RECORDS) j.append(r);
            j.close();
        }
        Path dir = tmp.resolve("node");
        Files.createDirectories(dir.resolve("fs"));
        Files.writeString(dir.resolve("fs/volumes.properties"), "volume.1.kind=memory\n");
        Backup.export(dir, FIXTURES.resolve("backup.nrbak"), PASS, false, CHEAP);
        byte[] payload = "payload of the 1.0 fixture".getBytes(StandardCharsets.UTF_8);
        var d = new PackageManifest.Draft("org.example.compat", "1.0.0", 1, PackageManifest.Sandboxing.PROCESS,
                "org.example.compat.Main", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload)), payload.length, "compat", List.of("fs.read:/mnt/data"), List.of(), "fixture");
        PackageManifest m = PackageManifest.parse(d.toBytes());
        SigningKey key = publisherKey();
        byte[] signed = m.withSignature(key.publicKey(), key.sign(PackageManifest.SIGNATURE_DOMAIN, m.body()));
        Files.write(FIXTURES.resolve("package.nrpkg"), new PackageArchive(signed, payload).toBytes());
    }

    @Test
    void release10FilesStillOpen() throws Exception {
        if (Boolean.getBoolean("noray.compat.write")) return;
        // keyring and encrypted namespace journal
        Path keyring = tmp.resolve("keyring");
        Files.copy(FIXTURES.resolve("keyring"), keyring, StandardCopyOption.REPLACE_EXISTING);
        try (Keyring k = Keyring.openOrCreate(keyring, PASS, CHEAP)) {
            Path journal = tmp.resolve("namespace.journal");
            Files.copy(FIXTURES.resolve("namespace.journal"), journal);
            FileNamespaceJournal j = new FileNamespaceJournal(journal, true, PayloadCipher.aead(k.subKey("namespace-journal")));
            assertEquals(RECORDS, j.load());
            j.close();
        }
        // backup
        var entries = Backup.verify(FIXTURES.resolve("backup.nrbak"), PASS);
        assertEquals("fs/volumes.properties", entries.getFirst().path());
        // signed package
        PackageArchive a = PackageArchive.read(Files.readAllBytes(FIXTURES.resolve("package.nrpkg")));
        PublisherRegistry trust = new PublisherRegistry(tmp.resolve("publishers.properties"));
        trust.put(new Publisher("compat", List.of(publisherKey().publicKey()), 1, false));
        assertEquals("org.example.compat 1.0.0", PackageVerifier.verify(a.manifest(), a.payload(), trust).manifest().toString());
    }
}
