package com.redactado.noray.net.enroll;

import com.redactado.noray.net.NodeId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class FileEnrollmentStore implements EnrollmentStore {

    private final Path dir;

    public FileEnrollmentStore(Path dataDir) {
        this.dir = Objects.requireNonNull(dataDir, "dataDir").resolve("net").resolve("enroll");
    }

    @Override
    public synchronized void save(EnrollmentRecord record) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(record.nodeId().hex() + ".enr");
            Path tmp = dir.resolve(record.nodeId().hex() + ".enr.tmp");
            String body = "nodeId=" + record.nodeId().hex() + "\n"
                    + "fsStore=" + record.fsStore() + "\n"
                    + "repo=" + record.repo() + "\n"
                    + "tags=" + String.join(",", record.tags()) + "\n"
                    + "introHint=" + record.introHint() + "\n"
                    + "x25519pub=" + record.x25519Pub() + "\n"
                    + "mlkem768pub=" + record.mlkemPub() + "\n";
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save enrollment " + record.nodeId(), e);
        }
    }

    @Override
    public synchronized void delete(NodeId nodeId) {
        try {
            Files.deleteIfExists(dir.resolve(nodeId.hex() + ".enr"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete enrollment " + nodeId, e);
        }
    }

    @Override
    public synchronized Optional<EnrollmentRecord> find(NodeId nodeId) {
        Path file = dir.resolve(nodeId.hex() + ".enr");
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(readFile(file));
    }

    @Override
    public synchronized List<EnrollmentRecord> list() {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> stream = Files.list(dir)) {
            List<EnrollmentRecord> out = new ArrayList<>();
            for (Path p : stream.filter(x -> x.getFileName().toString().endsWith(".enr")).toList()) {
                out.add(readFile(p));
            }
            return List.copyOf(out);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list enrollments", e);
        }
    }

    private static EnrollmentRecord readFile(Path file) {
        try {
            Map<String, String> map = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
            NodeId id = new NodeId(map.getOrDefault("nodeId", ""));
            boolean fsStore = Boolean.parseBoolean(map.getOrDefault("fsStore", "false"));
            boolean repo = Boolean.parseBoolean(map.getOrDefault("repo", "false"));
            String tagsRaw = map.getOrDefault("tags", "");
            Set<String> tags = new LinkedHashSet<>();
            if (!tagsRaw.isEmpty()) {
                for (String t : tagsRaw.split(",")) {
                    String s = t.trim();
                    if (!s.isEmpty()) tags.add(s);
                }
            }
            return new EnrollmentRecord(
                    id, fsStore, repo, Set.copyOf(tags),
                    map.getOrDefault("introHint", ""),
                    map.getOrDefault("x25519pub", ""),
                    map.getOrDefault("mlkem768pub", "")
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + file, e);
        }
    }
}
