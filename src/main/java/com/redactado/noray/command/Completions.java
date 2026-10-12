package com.redactado.noray.command;

import com.redactado.noray.filesystem.api.DirectoryEntry;
import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.api.NodeType;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.kernel.api.KernelContext;

import java.util.ArrayList;
import java.util.List;

/** Shared tab-completion helpers. */
public final class Completions {

    private Completions() {
    }

    /** Paths of the Noray namespace starting with {@code partial}; directories end with "/". */
    public static List<String> vfsPaths(KernelContext ctx, String partial, boolean directoriesOnly) {
        FileSystemService fs = ctx.getServiceRegistry().get(FileSystemService.class).orElse(null);
        if (fs == null) return List.of();
        String p = partial.isEmpty() ? "/" : partial;
        if (!p.startsWith("/")) return List.of();
        int slash = p.lastIndexOf('/');
        String dir = slash <= 0 ? "/" : p.substring(0, slash);
        List<String> out = new ArrayList<>();
        try {
            for (DirectoryEntry e : fs.readdir(VfsPath.parse(dir))) {
                boolean isDir = e.type() == NodeType.DIRECTORY;
                if (directoriesOnly && !isDir) continue;
                out.add((dir.equals("/") ? "/" : dir + "/") + e.name() + (isDir ? "/" : ""));
            }
        } catch (RuntimeException ignored) {
            // not a directory, not mounted, or not allowed
        }
        return out;
    }
}
