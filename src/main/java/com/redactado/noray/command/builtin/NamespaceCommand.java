package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.command.Completions;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.api.NamespaceAdmin;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.web.ui.Json;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Quotas and fine-grained rules on directories. */
public final class NamespaceCommand implements Command {

    @Override public String name() { return "ns"; }
    @Override public String description() { return "Directory quotas and permissions (read-only, no-delete, write-once)"; }
    @Override public String synopsis() { return "ns show|set|clear|usage ..."; }
    @Override public CommandCategory category() { return CommandCategory.FILESYSTEM; }
    @Override public Set<String> switchFlags() { return Set.of("json"); }
    @Override public List<String> subcommands() { return List.of("show", "set", "clear", "usage"); }

    @Override
    public List<String> complete(KernelContext ctx, List<String> args) {
        if (args.size() <= 1) return subcommands();
        if (args.size() == 2) return Completions.vfsPaths(ctx, args.get(1), true);
        if (args.size() == 3 && (args.get(0).equals("set") || args.get(0).equals("clear"))) {
            return NamespaceAdmin.KEYS.stream().sorted().toList();
        }
        return List.of();
    }

    @Override
    public String longHelp() {
        return """
            Usage: ns show DIR                 the rules set on DIR and what its subtree uses
                   ns set DIR KEY VALUE        set a rule
                   ns clear DIR KEY            remove a rule
                   ns usage DIR                bytes and files under DIR, and the raw storage they take
                   --json                      (show, usage) machine-readable output
                                               (estimate: redundancy of each file's storage class, before compression)

            Keys:
              quota.bytes N      the whole subtree may hold at most N bytes of content
              quota.files N      ... at most N files
              max.file.bytes N   no single file larger than N bytes
              readonly true      nothing can be created, written or deleted below DIR
              deny.delete true   files can be created and written, but never deleted
              deny.overwrite true   a file can be written once; it cannot be replaced

            Rules are inherited; the nearest directory that sets a key decides, so a subdirectory can relax a
            parent (for example readonly false). Quotas add up: every ancestor that has one must have room.
            Existing data is never touched: lowering a quota below the current usage only blocks growth.
            Combine deny.delete + deny.overwrite for an evidence/archive directory that can only receive new files.
            """;
    }

    @Override
    public int execute(CommandInvocation inv) throws Exception {
        var out = inv.out();
        var args = inv.args();
        NamespaceAdmin admin = inv.context().getServiceRegistry().get(NamespaceAdmin.class).orElse(null);
        if (admin == null) {
            out.println("ns: the filesystem service is not available");
            return 1;
        }
        if (args.isEmpty()) {
            out.println(longHelp());
            return 1;
        }
        try {
            switch (args.get(0)) {
                case "show" -> {
                    if (args.size() != 2) return usage(inv, "ns show DIR");
                    VfsPath dir = VfsPath.parse(args.get(1));
                    Map<String, String> attrs = new TreeMap<>(admin.attrsOf(dir));
                    var u = admin.usageOf(dir);
                    if (!inv.hasFlag("json")) {
                        if (attrs.isEmpty()) out.println("(no rules set on " + dir + ")");
                        attrs.forEach((k, v) -> out.println(k + " = " + v));
                    } else {
                        StringBuilder sb = new StringBuilder("{\"dir\":").append(json(dir.toString())).append(",\"rules\":{");
                        int i = 0;
                        for (var e : attrs.entrySet()) {
                            if (i++ > 0) sb.append(',');
                            sb.append(json(e.getKey())).append(':').append(json(e.getValue()));
                        }
                        sb.append("},\"bytes\":").append(u.bytes()).append(",\"files\":").append(u.files())
                                .append(",\"storedBytes\":").append(admin.storedBytesOf(dir)).append('}');
                        out.println(sb);
                        return 0;
                    }
                    out.printf("usage: %d bytes in %d file(s), about %d bytes stored with redundancy%n",
                            u.bytes(), u.files(), admin.storedBytesOf(dir));
                    return 0;
                }
                case "set" -> {
                    if (args.size() != 4) return usage(inv, "ns set DIR KEY VALUE");
                    admin.setAttr(VfsPath.parse(args.get(1)), args.get(2), args.get(3));
                    out.println("ok");
                    return 0;
                }
                case "clear" -> {
                    if (args.size() != 3) return usage(inv, "ns clear DIR KEY");
                    admin.setAttr(VfsPath.parse(args.get(1)), args.get(2), "");
                    out.println("ok");
                    return 0;
                }
                case "usage" -> {
                    if (args.size() != 2) return usage(inv, "ns usage DIR");
                    VfsPath dir = VfsPath.parse(args.get(1));
                    var u = admin.usageOf(dir);
                    if (inv.hasFlag("json")) {
                        out.println("{\"dir\":" + json(dir.toString()) + ",\"bytes\":" + u.bytes() + ",\"files\":" + u.files()
                                + ",\"storedBytes\":" + admin.storedBytesOf(dir) + "}");
                        return 0;
                    }
                    out.printf("%d bytes in %d file(s); about %d bytes stored with redundancy%n",
                            u.bytes(), u.files(), admin.storedBytesOf(dir));
                    return 0;
                }
                default -> {
                    out.println(longHelp());
                    return 1;
                }
            }
        } catch (FsException e) {
            out.println("ns: " + e.getMessage());
            return 1;
        }
    }

    private static String json(String s) {
        return Json.quote(s);
    }

    private static int usage(CommandInvocation inv, String text) {
        inv.out().println("Usage: " + text);
        return 1;
    }
}
