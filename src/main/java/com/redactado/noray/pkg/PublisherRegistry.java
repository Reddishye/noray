package com.redactado.noray.pkg;

import com.redactado.noray.pkg.PackageException.Reason;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/** The publishers pinned on this node, kept in {@code <packages>/publishers.properties}. */
public final class PublisherRegistry {

    private final Path file;
    private final Map<String, Publisher> publishers = new TreeMap<>();

    public PublisherRegistry(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            Properties p = new Properties();
            p.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
            for (String key : p.stringPropertyNames()) {
                if (!key.startsWith("publisher.") || !key.endsWith(".keys")) continue;
                String name = key.substring("publisher.".length(), key.length() - ".keys".length());
                List<byte[]> keys = new ArrayList<>();
                for (String k : p.getProperty(key).split(",")) keys.add(HexFormat.of().parseHex(k.strip()));
                int threshold = Integer.parseInt(p.getProperty("publisher." + name + ".threshold", "1"));
                boolean trusted = Boolean.parseBoolean(p.getProperty("publisher." + name + ".trusted", "false"));
                publishers.put(name, new Publisher(name, keys, threshold, trusted));
            }
        } catch (IOException | IllegalArgumentException e) {
            // a damaged trust file must stop installs, not silently trust nobody or everybody
            throw new PackageException(Reason.IO, "cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    public synchronized Optional<Publisher> get(String name) {
        return Optional.ofNullable(publishers.get(name));
    }

    public synchronized List<Publisher> all() {
        return List.copyOf(publishers.values());
    }

    public synchronized void put(Publisher publisher) {
        publishers.put(publisher.name(), publisher);
        save();
    }

    public synchronized boolean remove(String name) {
        boolean had = publishers.remove(name) != null;
        if (had) save();
        return had;
    }

    private void save() {
        StringBuilder sb = new StringBuilder("# pinned package publishers (pkg publisher add|remove)\n");
        for (Publisher p : publishers.values()) {
            sb.append("publisher.").append(p.name()).append(".keys=").append(String.join(",", p.keysHex())).append('\n');
            sb.append("publisher.").append(p.name()).append(".threshold=").append(p.threshold()).append('\n');
            sb.append("publisher.").append(p.name()).append(".trusted=").append(p.trusted()).append('\n');
        }
        try {
            StoreFiles.writeAtomically(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PackageException(Reason.IO, "cannot write " + file, e);
        }
    }
}
