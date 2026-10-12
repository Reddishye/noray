package com.redactado.noray.web.kit;

import com.redactado.noray.filesystem.api.DirectoryEntry;
import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.api.NodeType;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.web.html.Node;
import com.redactado.noray.web.ui.Component;
import com.redactado.noray.web.ui.Render;
import com.redactado.noray.web.ui.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.redactado.noray.web.html.Html.*;
import static com.redactado.noray.web.kit.Kit.*;

/**
 * Browses a subtree of a {@link FileSystemService} and previews files. Give it the module's own (scoped) service: it can
 * show nothing the module could not read, and it never goes above {@code root}.
 */
public final class FileBrowser implements Component {

    private final FileSystemService fs;
    private final VfsPath root;
    private final String title;
    private VfsPath dir;
    private VfsPath selected;
    private boolean writable;

    public FileBrowser(FileSystemService fs, VfsPath root, String title) {
        this.fs = fs;
        this.root = root;
        this.title = title;
        this.dir = root;
    }

    /**
     * Shows an upload form and a "new folder" field. Writes go through the same service, so namespace quotas, rules and
     * the module's grants all apply; existing files are not overwritten.
     */
    public FileBrowser allowWrites() {
        this.writable = true;
        return this;
    }

    /** Opens a path below the root (ignored if it is outside). */
    public FileBrowser open(VfsPath path) {
        if (within(path)) dir = path;
        return this;
    }

    @Override
    public Node render(Render r) {
        List<Node> crumbLinks = new ArrayList<>();
        for (VfsPath p = dir; p != null && within(p); p = p.isRoot() ? null : p.parent()) {
            VfsPath target = p;
            crumbLinks.addFirst(li(target.equals(dir) && selected == null ? span(aria("current", "page"), label(target))
                    : a(href("#"), r.onViewClick(() -> { dir = target; selected = null; }), label(target))));
        }
        List<DirectoryEntry> entries;
        Node listing;
        try {
            entries = new ArrayList<>(fs.readdir(dir));
            entries.sort(Comparator.comparing((DirectoryEntry e) -> e.type() != NodeType.DIRECTORY).thenComparing(DirectoryEntry::name));
            List<Node> rows = new ArrayList<>();
            if (!dir.equals(root)) {
                rows.add(tr(td(a(href("#"), r.onViewClick(() -> { dir = dir.parent(); selected = null; }), span(cls("nr-file-icon"), "↰"), "..")),
                        td(cls("nr-muted"), "parent")));
            }
            for (DirectoryEntry e : entries) {
                VfsPath child = dir.resolve(e.name());
                boolean isDir = e.type() == NodeType.DIRECTORY;
                rows.add(tr(child.equals(selected) ? aria("selected", true) : null,
                        td(a(href("#"), r.onViewClick(() -> {
                            if (isDir) { dir = child; selected = null; } else selected = child;
                        }), span(cls("nr-file-icon"), aria("hidden", "true"), isDir ? "▸" : "·"), e.name())),
                        td(cls("nr-muted"), isDir ? "folder" : e.type().name().toLowerCase())));
            }
            if (entries.isEmpty()) rows.add(tr(td(colspan(2), div(cls("nr-empty"), "This folder is empty."))));
            listing = rows.isEmpty() ? empty("This folder is empty.")
                    : div(cls("nr-table-wrap"), table(cls("nr-table"), caption(cls("nr-visually-hidden"), "Contents of " + dir),
                    thead(tr(th(scope("col"), "Name"), th(scope("col"), "Kind"))), tbody(rows)));
        } catch (FsException e) {
            listing = alert(Tone.ERROR, "Cannot list " + dir, e.getMessage());
        }
        Node preview = null;
        if (selected != null) {
            VfsPath file = selected;
            preview = card(file.name(), FilePreview.render(r, new FilePreview.FileInfo(file.name(), file.toString(),
                    () -> fs.readFully(file))));
        }
        return stack(flushCard(title, List.of(), div(cls("nr-card-b"), nav(aria("label", "Folder"), ol(cls("nr-crumbs"), crumbLinks))),
                        writable ? writeForms(r) : null, listing),
                preview);
    }

    private Node writeForms(Render r) {
        VfsPath target = dir;
        return div(cls("nr-pager"),
                form(r.onUpload(e -> {
                    int ok = 0;
                    for (var f : e.form().files()) {
                        if (!f.hasSafeName()) {
                            e.ctx().toast(Toast.Level.ERROR, "Refused file name: " + f.filename());
                            continue;
                        }
                        VfsPath p = target.resolve(f.filename());
                        try {
                            fs.createFile(p);
                            fs.writeFully(p, f.data());
                            ok++;
                        } catch (FsException ex) {
                            e.ctx().toast(Toast.Level.ERROR, f.filename() + ": " + ex.getMessage());
                        }
                    }
                    if (ok > 0) e.ctx().toast(Toast.Level.SUCCESS, "Uploaded " + ok + " file(s) to " + target);
                }), cls("nr-form-row"),
                        el("label", cls("nr-field"), span("Upload to " + target), input(type("file"), name("files"), multiple(), required())),
                        el("button", type("submit"), cls("nr-btn"), "Upload")),
                form(r.onSubmit(e -> {
                    String n = e.form().get("folder", "").strip();
                    if (n.isEmpty() || n.contains("/") || n.startsWith(".")) {
                        e.ctx().toast(Toast.Level.ERROR, "Folder names cannot be empty, contain / or start with a dot");
                        return;
                    }
                    try {
                        fs.mkdir(target.resolve(n));
                    } catch (FsException ex) {
                        e.ctx().toast(Toast.Level.ERROR, ex.getMessage());
                    }
                }), cls("nr-form-row"),
                        el("label", cls("nr-field"), span("New folder"), input(type("text"), name("folder"), placeholder("name"), required())),
                        el("button", type("submit"), cls("nr-btn"), "Create")));
    }

    private String label(VfsPath p) {
        return p.equals(root) ? (root.isRoot() ? "/" : root.name()) : p.name();
    }

    private boolean within(VfsPath p) {
        String r = root.toString(), s = p.toString();
        return root.isRoot() || s.equals(r) || s.startsWith(r.endsWith("/") ? r : r + "/");
    }
}
