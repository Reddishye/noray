package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.data.GcReport;
import com.redactado.noray.data.placement.DataCluster;
import com.redactado.noray.data.placement.MeasuredCluster;
import com.redactado.noray.data.placement.StorageNode;
import com.redactado.noray.data.placement.Tier;
import com.redactado.noray.data.tier.PolicyCatalog;
import com.redactado.noray.filesystem.api.StorageAdmin;
import com.redactado.noray.filesystem.store.ObjectStore;
import com.redactado.noray.filesystem.store.remote.BlobResponder;
import com.redactado.noray.filesystem.store.remote.DeleteQuarantine;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.net.NodeId;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Operates the data engine behind pool mounts: policies, repair, garbage collection, tiering and nodes. */
public final class StorageCommand implements Command {

    @Override public Set<String> switchFlags() { return Set.of("scrub", "now", "dry-run", "all"); }

    @Override public String name() { return "storage"; }
    @Override public String description() { return "Storage policies, repair, garbage collection and tiering of pool mounts"; }
    @Override public String synopsis() { return "storage policy|class|repair|gc|tier|nodes|quarantine|quota ..."; }
    @Override public CommandCategory category() { return CommandCategory.FILESYSTEM; }

    @Override
    public String longHelp() {
        return """
            Usage: storage policy list
                   storage policy get PATH
                   storage policy set DIR NAME      (NAME "" restores inheritance)
                   storage policy apply DIR         re-store files so they match their policy
                   storage class PATH               policy a file is stored with right now
                   storage repair [--scrub] [--now] restore redundancy after lost nodes / bad data
                   storage gc [--dry-run]           delete blobs no file needs
                   storage tier                     one automatic hot/cold pass
                   storage nodes                    nodes, their weights and measured speed
                   storage quarantine list|restore ID|restore --all|sweep
                   storage quota                      what each peer occupies on this node (noray.fs.blob.peer.quota.mib)
                   storage quota attribute PEER       charge every unattributed blob to PEER
                                                    blobs peers deleted but that are still recoverable

            repair  --now   treat every unreachable node as lost instead of waiting for the grace period
                    --scrub read every blob back and verify it (slow, finds silent corruption)
            gc      removes only blobs on engine-dedicated volumes (engine=true) and aborts if any file's
                    manifest cannot be read. Use --dry-run first.

            Policies are inherited by subdirectories. Writing never changes existing data by itself:
            use 'policy apply' (or an auto policy, which moves files by how much they are used).
            """;
    }

    @Override
    public int execute(CommandInvocation inv) throws Exception {
        var out = inv.out();
        var args = inv.args();
        var registry = inv.context().getServiceRegistry();
        StorageAdmin admin = registry.get(StorageAdmin.class).orElse(null);
        if (admin == null) {
            out.println("storage: the filesystem service is not available");
            return 1;
        }
        if (args.isEmpty()) {
            out.println(longHelp());
            return 1;
        }
        try {
            switch (args.get(0)) {
                case "policy" -> {
                    return policy(inv, admin, registry.get(PolicyCatalog.class).orElse(null));
                }
                case "class" -> {
                    if (args.size() != 2) return usage(inv, "storage class PATH");
                    String c = admin.storageClassOf(VfsPath.parse(args.get(1)));
                    out.println(c.isEmpty() ? "(no engine data)" : c);
                    return 0;
                }
                case "repair" -> {
                    boolean scrub = flag(inv, "scrub");
                    if (flag(inv, "now")) {
                        admin.observeNodes();
                    }
                    var r = admin.repair(scrub, flag(inv, "now"));
                    out.printf("files: %d, repaired: %d, failed: %d%n", r.files(), r.filesRepaired(), r.filesFailed());
                    out.printf("pieces rebuilt: %d, variants recreated: %d, pages restored: %d%n",
                            r.piecesRebuilt(), r.variantsRecreated(), r.pagesRestored());
                    if (r.chunksLost() > 0) {
                        out.printf("LOST: %d chunk(s) could not be recovered%n", r.chunksLost());
                        return 2;
                    }
                    return r.filesFailed() > 0 ? 1 : 0;
                }
                case "gc" -> {
                    GcReport g = admin.gc(flag(inv, "dry-run"));
                    out.printf("%s: scanned %d blob(s) on %d node(s), %s %d (%d bytes), protected %d%n",
                            g.dryRun() ? "dry run" : "gc", g.blobsScanned(), g.nodesScanned(),
                            g.dryRun() ? "would delete" : "deleted", g.blobsDeleted(), g.bytesFreed(), g.blobsProtected());
                    if (!g.nodesSkipped().isEmpty()) {
                        out.println("skipped nodes: " + String.join(", ", g.nodesSkipped()));
                    }
                    return 0;
                }
                case "tier" -> {
                    var t = admin.autoTier();
                    out.printf("evaluated: %d, demoted: %d, promoted: %d, failed: %d%n",
                            t.evaluated(), t.demoted(), t.promoted(), t.failed());
                    return t.failed() > 0 ? 1 : 0;
                }
                case "quarantine" -> {
                    return quarantine(inv, registry.get(BlobResponder.class).orElse(null));
                }
                case "quota" -> {
                    var q = registry.get(BlobResponder.class).flatMap(BlobResponder::quota).orElse(null);
                    if (q == null) {
                        out.println("storage: no per-peer quota on this node (noray.fs.blob.peer.quota.mib)");
                        return 1;
                    }
                    var store = registry.get(ObjectStore.class).orElse(null);
                    if (args.size() >= 3 && args.get(1).equals("attribute")) {
                        if (store == null) return usage(inv, "storage quota attribute PEER (no local store)");
                        int n = q.attributeUnclaimed(new NodeId(args.get(2)), store);
                        out.println("charged " + n + " unattributed blob(s) to " + args.get(2));
                        return 0;
                    }
                    out.println("limit per peer: " + q.limitBytes() / (1024 * 1024) + " MiB");
                    q.usage().forEach((peer, bytes) -> out.printf("%s  %d bytes  (%d%%)%n",
                            peer.hex(), bytes, bytes * 100 / q.limitFor(peer)));
                    if (store != null) {
                        long[] u = q.unattributed(store);
                        if (u[0] > 0) {
                            out.printf("unattributed: %d blob(s), %d bytes (stored before the ledger, or by this node);%n"
                                    + "  a peer that stores one again is charged for it; `storage quota attribute PEER` assigns them all%n",
                                    u[0], u[1]);
                        }
                    }
                    return 0;
                }
                case "nodes" -> {
                    DataCluster cluster = registry.get(DataCluster.class).orElse(null);
                    if (cluster == null) {
                        out.println("storage: no data cluster");
                        return 1;
                    }
                    out.printf("%-24s %-6s %-10s %10s %10s  %s%n", "NODE", "STATE", "DOMAIN", "CAPACITY", "MB/s", "TIERS");
                    for (StorageNode n : cluster.nodes()) {
                        String tiers = (n.accepts(Tier.HOT) ? "hot " : "") + (n.accepts(Tier.COLD) ? "cold" : "");
                        out.printf("%-24s %-6s %-10s %10.1f %10.1f  %s%n", n.id(), n.available() ? "up" : "DOWN",
                                n.domain(), n.capacityWeight(), n.speedWeight(), tiers.trim());
                    }
                    if (cluster instanceof MeasuredCluster) {
                        out.println("(MB/s is measured once a node has served enough traffic, configured before that)");
                    }
                    return 0;
                }
                default -> {
                    out.println("storage: unknown action '" + args.get(0) + "'");
                    return 1;
                }
            }
        } catch (Exception e) {
            out.println("storage: " + e.getMessage());
            return 1;
        }
    }

    private int quarantine(CommandInvocation inv, BlobResponder responder) {
        var out = inv.out();
        List<String> a = inv.args();
        DeleteQuarantine q = responder == null ? null : responder.quarantine().orElse(null);
        if (q == null) {
            out.println("storage: remote deletes are immediate on this node (noray.fs.blob.delete.delay.h = 0)");
            return 1;
        }
        String action = a.size() > 1 ? a.get(1) : "list";
        switch (action) {
            case "list" -> {
                var entries = q.list();
                out.println(entries.size() + " blob(s) waiting to be deleted (delay " + q.delay().toHours() + " h)");
                for (var e : entries) {
                    out.printf("%s  removed for good at %s%n", e.id().hex(), java.time.Instant.ofEpochMilli(e.deadlineMillis()));
                }
                return 0;
            }
            case "restore" -> {
                if (flag(inv, "all")) {
                    out.println(q.restoreAll() + " blob(s) restored");
                    return 0;
                }
                if (a.size() != 3) return usage(inv, "storage quarantine restore ID | --all");
                boolean ok = q.restore(new com.redactado.noray.filesystem.store.ObjectId(a.get(2)));
                out.println(ok ? "restored" : "not in quarantine");
                return ok ? 0 : 1;
            }
            case "sweep" -> {
                out.println(q.sweep() + " blob(s) deleted for good");
                return 0;
            }
            default -> {
                return usage(inv, "storage quarantine list|restore ID|restore --all|sweep");
            }
        }
    }

    private int policy(CommandInvocation inv, StorageAdmin admin, PolicyCatalog catalog) throws Exception {
        var out = inv.out();
        List<String> a = inv.args();
        if (a.size() < 2) {
            return usage(inv, "storage policy list|get|set|apply ...");
        }
        switch (a.get(1)) {
            case "list" -> {
                if (catalog == null) {
                    out.println("storage: no policy catalog");
                    return 1;
                }
                for (Map.Entry<String, String> e : catalog.describe().entrySet()) {
                    out.printf("%-10s %s%n", e.getKey(), e.getValue());
                }
                return 0;
            }
            case "get" -> {
                if (a.size() != 3) return usage(inv, "storage policy get PATH");
                out.println(admin.policyOf(VfsPath.parse(a.get(2))));
                return 0;
            }
            case "set" -> {
                if (a.size() < 3 || a.size() > 4) return usage(inv, "storage policy set DIR NAME");
                admin.setPolicy(VfsPath.parse(a.get(2)), a.size() == 4 ? a.get(3) : "");
                return 0;
            }
            case "apply" -> {
                if (a.size() != 3) return usage(inv, "storage policy apply DIR");
                out.println(admin.applyPolicy(VfsPath.parse(a.get(2))) + " file(s) changed");
                return 0;
            }
            default -> {
                out.println("storage: unknown policy action '" + a.get(1) + "'");
                return 1;
            }
        }
    }

    private static boolean flag(CommandInvocation inv, String name) {
        return inv.hasFlag(name) || inv.args().contains("--" + name);
    }

    private static int usage(CommandInvocation inv, String text) {
        inv.out().println("Usage: " + text);
        return 1;
    }
}
