package com.redactado.noray.net.identity;

import com.redactado.noray.net.NodeId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class LocalIdentityStore {

    private final Path file;

    public LocalIdentityStore(Path dataDir) {
        this.file = Objects.requireNonNull(dataDir, "dataDir").resolve("net").resolve("local.id");
    }

    public LocalIdentity loadOrCreate() {
        try {
            if (Files.isRegularFile(file)) {
                String hex = Files.readString(file, StandardCharsets.UTF_8).trim();
                return new LocalIdentity(new NodeId(hex));
            }
            Files.createDirectories(file.getParent());
            byte[] seed = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed);
            String hex = HexFormat.of().formatHex(digest);
            Path tmp = file.resolveSibling("local.id.tmp");
            Files.writeString(tmp, hex + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return new LocalIdentity(new NodeId(hex));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to load/create local NodeId", e);
        }
    }
}