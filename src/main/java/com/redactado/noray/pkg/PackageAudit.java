package com.redactado.noray.pkg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only record of everything done to packages (install, approve, activate, refusals...). Each line carries the
 * SHA-256 of the previous line, so an edited or deleted entry breaks the chain ({@link #verify()}); it is evidence of
 * tampering, not a prevention (whoever can rewrite the data directory can rewrite the whole chain).
 */
public final class PackageAudit {

    public record Entry(long seq, String ts, String event, String subject, String detail) {
    }

    private final Path file;
    private String lastHash = "0".repeat(64);
    private long seq;

    public PackageAudit(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                seq = lines.size();
                if (!lines.isEmpty()) lastHash = StoreFiles.sha256Hex(lines.getLast().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new PackageException(PackageException.Reason.IO, "cannot read " + file, e);
        }
    }

    public synchronized void record(String event, String subject, String detail) {
        String line = "{\"seq\":" + (++seq) + ",\"ts\":\"" + Instant.now() + "\",\"event\":" + json(event)
                + ",\"subject\":" + json(subject) + ",\"detail\":" + json(detail) + ",\"prev\":\"" + lastHash + "\"}";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new PackageException(PackageException.Reason.IO, "cannot append to " + file, e);
        }
        lastHash = StoreFiles.sha256Hex(line.getBytes(StandardCharsets.UTF_8));
    }

    /** The last {@code max} entries, oldest first. */
    public synchronized List<String> tail(int max) {
        try {
            if (!Files.exists(file)) return List.of();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            return new ArrayList<>(lines.subList(Math.max(0, lines.size() - max), lines.size()));
        } catch (IOException e) {
            throw new PackageException(PackageException.Reason.IO, "cannot read " + file, e);
        }
    }

    /** -1 if the chain is intact, else the 1-based number of the first line whose {@code prev} does not match. */
    public synchronized long verify() {
        try {
            if (!Files.exists(file)) return -1;
            String prev = "0".repeat(64);
            long n = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                n++;
                if (!line.contains("\"prev\":\"" + prev + "\"")) return n;
                prev = StoreFiles.sha256Hex(line.getBytes(StandardCharsets.UTF_8));
            }
            return -1;
        } catch (IOException e) {
            throw new PackageException(PackageException.Reason.IO, "cannot read " + file, e);
        }
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : (s == null ? "" : s).toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
