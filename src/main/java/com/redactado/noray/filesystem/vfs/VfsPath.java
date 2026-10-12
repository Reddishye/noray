package com.redactado.noray.filesystem.vfs;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Absolute VFS path. Always starts with '/', no trailing slash except root.
 * No '.' or '..' after normalize.
 */
public final class VfsPath {
    private static final VfsPath ROOT = new VfsPath("/", Collections.emptyList());

    private final String path;
    private final List<String> segments;

    private VfsPath(String path, List<String> segments) {
        this.path = path;
        this.segments = Collections.unmodifiableList(segments);
    }

    public static VfsPath root() {
        return ROOT;
    }

    public static VfsPath parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new FsException(FsErrorCode.INVALID_PATH, "Path cannot be null or empty");
        }
        if (raw.indexOf('\0') != -1) {
            throw new FsException(FsErrorCode.INVALID_PATH, "Path cannot contain NUL byte");
        }
        if (!raw.startsWith("/")) {
            throw new FsException(FsErrorCode.INVALID_PATH, "Path must start with '/'");
        }

        String[] parts = raw.split("/+");
        List<String> segs = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.equals(".") || part.equals("..")) {
                throw new FsException(FsErrorCode.INVALID_PATH, "Path cannot contain '.' or '..'");
            }
            segs.add(part);
        }

        if (segs.isEmpty()) {
            return ROOT;
        }

        String normalized = "/" + String.join("/", segs);
        return new VfsPath(normalized, segs);
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    public VfsPath parent() {
        if (isRoot()) {
            return null;
        }
        if (segments.size() == 1) {
            return ROOT;
        }
        List<String> parentSegs = segments.subList(0, segments.size() - 1);
        String p = "/" + String.join("/", parentSegs);
        return new VfsPath(p, parentSegs);
    }

    public String name() {
        if (isRoot()) {
            return "";
        }
        return segments.get(segments.size() - 1);
    }

    public VfsPath resolve(String child) {
        Objects.requireNonNull(child, "child");
        if (child.startsWith("/")) {
            return parse(child);
        }
        if (isRoot()) {
            return parse("/" + child);
        }
        return parse(path + "/" + child);
    }

    public List<String> segments() {
        return segments;
    }

    @Override
    public String toString() {
        return path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VfsPath vfsPath = (VfsPath) o;
        return Objects.equals(path, vfsPath.path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }
}
