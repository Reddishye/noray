package com.redactado.noray.web.kit;

import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.api.NodeType;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.web.Site;
import com.redactado.noray.web.ui.RequestContext;

import java.util.Optional;

/**
 * A public site served from a filesystem subtree: {@code index.html} for directories, a redirect to the trailing-slash
 * form so relative links work, 404 for anything missing. Dot-files are never served. Give it a scoped, read-only view.
 */
public final class StaticSite implements Site {

    private final FileSystemService fs;
    private final VfsPath root;
    private final String mountPrefix;

    /** @param mountPrefix where the site is mounted ({@code "/"} or {@code "/blog"}), for redirects */
    public StaticSite(FileSystemService fs, VfsPath root, String mountPrefix) {
        this.fs = fs;
        this.root = root;
        this.mountPrefix = mountPrefix.equals("/") ? "" : mountPrefix;
    }

    @Override
    public Optional<Content> get(String path, RequestContext request) {
        VfsPath target = root;
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            if (seg.startsWith(".")) return Optional.empty(); // .git, .env, .packages ...
            target = target.resolve(seg);
        }
        try {
            NodeType type = fs.stat(target).type();
            if (type == NodeType.DIRECTORY) {
                if (!path.endsWith("/")) return Optional.of(new Redirect(mountPrefix + path + "/"));
                VfsPath index = target.resolve("index.html");
                return Optional.of(new FileContent("index.html", fs.readFully(index)));
            }
            if (type != NodeType.FILE) return Optional.empty();
            return Optional.of(new FileContent(target.name(), fs.readFully(target)));
        } catch (FsException e) {
            if (e.code() == FsErrorCode.NOT_FOUND || e.code() == FsErrorCode.DENIED) return Optional.empty();
            throw e;
        }
    }
}
