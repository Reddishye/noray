package com.redactado.noray.filesystem.capability;

import com.redactado.noray.filesystem.api.*;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.kernel.capability.Capability;
import com.redactado.noray.kernel.capability.CapabilityRegistry;
import com.redactado.noray.kernel.capability.CapabilitySet;
import com.redactado.noray.kernel.capability.ServiceAttenuator;

import java.util.List;
import java.util.Objects;

/**
 * A view of the filesystem limited to some subtrees. Paths outside every scope
 * are refused (not "not found": a module learns nothing about what lies outside).
 */
public final class ScopedFileSystemService implements FileSystemService {

    private final FileSystemService base;
    private final List<String> readScopes;
    private final List<String> writeScopes;
    private final List<String> createScopes;
    private final List<String> deleteScopes;
    private final String moduleId;
    private final CapabilityRegistry registry;

    public ScopedFileSystemService(FileSystemService base, List<String> readScopes, List<String> writeScopes,
                                   String moduleId, CapabilityRegistry registry) {
        this(base, readScopes, writeScopes, List.of(), List.of(), moduleId, registry);
    }

    /**
     * @param createScopes subtrees where the module may create entries and fill each file once ({@code fs.create})
     * @param deleteScopes subtrees where the module may delete entries ({@code fs.delete})
     */
    public ScopedFileSystemService(FileSystemService base, List<String> readScopes, List<String> writeScopes,
                                   List<String> createScopes, List<String> deleteScopes,
                                   String moduleId, CapabilityRegistry registry) {
        this.base = Objects.requireNonNull(base);
        this.readScopes = List.copyOf(readScopes);
        this.writeScopes = List.copyOf(writeScopes);
        this.createScopes = List.copyOf(createScopes);
        this.deleteScopes = List.copyOf(deleteScopes);
        this.moduleId = moduleId;
        this.registry = registry;
    }

    /** Registered with the kernel so sandboxed modules get a scoped view. */
    public static ServiceAttenuator<FileSystemService> attenuator() {
        return new ServiceAttenuator<>() {
            @Override public Class<FileSystemService> serviceType() { return FileSystemService.class; }
            @Override public FileSystemService attenuate(FileSystemService base, String moduleId,
                                                         CapabilitySet grants, CapabilityRegistry registry) {
                if (!grants.hasKind("fs.read", "fs.write", "fs.create", "fs.delete")) return null;
                return new ScopedFileSystemService(base, grants.scopes("fs.read"), grants.scopes("fs.write"),
                        grants.scopes("fs.create"), grants.scopes("fs.delete"), moduleId, registry);
            }
        };
    }

    private void checkRead(VfsPath p) {
        if (!within(readScopes, p) && !within(writeScopes, p)) deny("fs.read", p);
    }

    private void checkWrite(VfsPath p) {
        if (!within(writeScopes, p)) deny("fs.write", p);
    }

    private void checkCreate(VfsPath p) {
        if (!within(writeScopes, p) && !within(createScopes, p)) deny("fs.write", p, "fs.create");
    }

    private void checkDelete(VfsPath p) {
        if (!within(writeScopes, p) && !within(deleteScopes, p)) deny("fs.write", p, "fs.delete");
    }

    private static boolean within(List<String> scopes, VfsPath p) {
        String path = p.toString();
        for (String s : scopes) {
            if (s.equals("/")) return true;
            String t = s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
            if (path.equals(t) || path.startsWith(t + "/")) return true;
        }
        return false;
    }

    private void deny(String kind, VfsPath p) {
        deny(kind, p, null);
    }

    /** Denials are recorded as the grant an operator would normally give; {@code orNarrower} names a smaller one that would do. */
    private void deny(String kind, VfsPath p, String orNarrower) {
        String cap = Capability.of(kind, p.toString()).toString();
        if (registry != null) registry.deny(moduleId, cap);
        throw new FsException(FsErrorCode.DENIED, "Module [" + moduleId + "] lacks " + cap
                + (orNarrower == null ? "" : " (or " + orNarrower + ")"));
    }

    @Override public void mkdir(VfsPath path) { checkCreate(path); base.mkdir(path); }
    @Override public void createFile(VfsPath path) { checkCreate(path); base.createFile(path); }
    @Override public void unlink(VfsPath path) { checkDelete(path); base.unlink(path); }
    @Override public DirectoryEntry stat(VfsPath path) { checkRead(path); return base.stat(path); }
    @Override public List<DirectoryEntry> readdir(VfsPath path) { checkRead(path); return base.readdir(path); }

    @Override public ObjectId writeFully(VfsPath path, byte[] data) {
        if (!within(writeScopes, path)) {
            // create-only grant: the file may be filled once, never replaced
            checkCreate(path);
            if (base.hasContent(path)) deny("fs.write", path);
        }
        return base.writeFully(path, data);
    }

    @Override public byte[] readFully(VfsPath path) { checkRead(path); return base.readFully(path); }
    @Override public String version(VfsPath path) { checkRead(path); return base.version(path); }
    @Override public ObjectId writeIfVersion(VfsPath path, String expected, byte[] data) {
        checkWrite(path); // a conditional overwrite is still an overwrite: create-only grants do not cover it
        return base.writeIfVersion(path, expected, data);
    }
    @Override public boolean hasContent(VfsPath path) { checkRead(path); return base.hasContent(path); }
}
