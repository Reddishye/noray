package com.redactado.noray.filesystem.config;

import com.redactado.noray.filesystem.Metadata;
import com.redactado.noray.filesystem.MetadataItem;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.vfs.Mount;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.filesystem.volume.VolumeId;
import com.redactado.noray.util.SecureFiles;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeSet;
import java.util.UUID;

/**
 * {@link FsConfigStore} kept in a {@code java.util.Properties} file, written atomically
 * (temp file, fsync, rename) with owner-only permissions.
 *
 * <pre>
 * volume.&lt;uuid&gt;.&lt;key&gt;=value
 * mount.&lt;n&gt;.name / .point / .volume / .options
 * </pre>
 */
public final class FileFsConfigStore implements FsConfigStore {

    private static final String VOLUME = "volume.";
    private static final String MOUNT = "mount.";

    private final Path file;

    public FileFsConfigStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
    }

    @Override
    public synchronized FsConfig load() throws FsException {
        if (!Files.isRegularFile(file)) {
            return FsConfig.empty();
        }
        SecureFiles.restrictToOwner(file);
        Properties props = new Properties();
        try {
            props.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | IllegalArgumentException e) {
            throw new FsException(FsErrorCode.INTERNAL, "Cannot read filesystem config " + file, e);
        }
        try {
            return parse(props);
        } catch (RuntimeException e) {
            if (e instanceof FsException fe) {
                throw fe;
            }
            throw new FsException(FsErrorCode.INTERNAL, "Invalid filesystem config " + file + ": " + e.getMessage(), e);
        }
    }

    private static FsConfig parse(Properties props) {
        Map<VolumeId, Metadata.Builder> volumes = new LinkedHashMap<>();
        Map<Integer, Map<String, String>> mounts = new java.util.TreeMap<>();

        for (String key : new TreeSet<>(props.stringPropertyNames())) {
            String value = props.getProperty(key);
            if (key.startsWith(VOLUME)) {
                String rest = key.substring(VOLUME.length());
                int dot = rest.indexOf('.');
                if (dot <= 0 || dot == rest.length() - 1) {
                    throw new IllegalArgumentException("bad key '" + key + "'");
                }
                VolumeId id = new VolumeId(UUID.fromString(rest.substring(0, dot)));
                volumes.computeIfAbsent(id, k -> Metadata.builder()).put(rest.substring(dot + 1), value);
            } else if (key.startsWith(MOUNT)) {
                String rest = key.substring(MOUNT.length());
                int dot = rest.indexOf('.');
                if (dot <= 0 || dot == rest.length() - 1) {
                    throw new IllegalArgumentException("bad key '" + key + "'");
                }
                int n = Integer.parseInt(rest.substring(0, dot));
                mounts.computeIfAbsent(n, k -> new LinkedHashMap<>()).put(rest.substring(dot + 1), value);
            }
        }

        Map<VolumeId, Metadata> builtVolumes = new LinkedHashMap<>();
        volumes.forEach((id, b) -> builtVolumes.put(id, b.build()));

        List<Mount> builtMounts = new ArrayList<>();
        for (Map<String, String> m : mounts.values()) {
            String name = required(m, "name");
            String point = required(m, "point");
            String volume = required(m, "volume");
            builtMounts.add(new Mount(name, VfsPath.parse(point), new VolumeId(UUID.fromString(volume)),
                    m.getOrDefault("options", "")));
        }
        return new FsConfig(builtVolumes, builtMounts);
    }

    private static String required(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("mount entry is missing '" + key + "'");
        }
        return v;
    }

    @Override
    public synchronized void save(FsConfig config) throws FsException {
        Objects.requireNonNull(config, "config");
        Properties props = new Properties();
        config.volumes().forEach((id, meta) -> {
            for (MetadataItem item : meta.asMap().values()) {
                if (item.value() != null) {
                    props.setProperty(VOLUME + id.value() + "." + item.key(), item.value());
                }
            }
        });
        int n = 0;
        for (Mount m : config.mounts()) {
            props.setProperty(MOUNT + n + ".name", m.name());
            props.setProperty(MOUNT + n + ".point", m.mountPoint().toString());
            props.setProperty(MOUNT + n + ".volume", m.volumeId().value().toString());
            props.setProperty(MOUNT + n + ".options", m.options());
            n++;
        }

        try {
            Files.createDirectories(file.getParent());
            StringWriter sw = new StringWriter();
            props.store(sw, null);
            String body = sw.toString().lines()
                    .filter(l -> !l.startsWith("#")) // drop the timestamp comment for reproducible output
                    .sorted()
                    .collect(java.util.stream.Collectors.joining("\n", "", "\n"));

            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            SecureFiles.createOwnerOnly(tmp);
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "Cannot write filesystem config " + file, e);
        }
    }
}
