package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.PeerCapabilities;
import com.redactado.noray.net.api.NetFramework;
import com.redactado.noray.net.enroll.EnrollmentRecord;
import com.redactado.noray.net.enroll.EnrollmentStore;
import com.redactado.noray.net.internal.PersistingMeshTransport;
import com.redactado.noray.net.internal.RoutingMeshTransport;

import java.util.List;
import java.util.Set;

public final class NodenetCommand implements Command {

    @Override public String name() { return "nodenet"; }
    @Override public String description() { return "Show node id and enroll overlay peers"; }
    @Override public String synopsis() { return "nodenet id|peers|enroll NODEID X25519PUB MLKEMPUB [host:port]|revoke NODEID"; }
    @Override public CommandCategory category() { return CommandCategory.NETWORK; }

    @Override
    public int execute(CommandInvocation inv) {
        var ctx = inv.context();
        var net = ctx.getServiceRegistry().get(NetFramework.class).orElse(null);
        if (net == null) {
            inv.err().println("no net framework");
            return 1;
        }
        List<String> args = inv.args();
        if (args.isEmpty() || "id".equals(args.get(0))) {
            inv.out().println(net.localId().hex());
            return 0;
        }
        switch (args.get(0)) {
            case "peers" -> {
                EnrollmentStore store = ctx.getServiceRegistry().get(EnrollmentStore.class).orElse(null);
                if (store == null) {
                    for (NodeId id : net.enrolledPeers()) inv.out().println(id.hex());
                    return 0;
                }
                for (EnrollmentRecord rec : store.list()) {
                    inv.out().printf("%s hint=%s x25519=%s%n",
                            rec.nodeId().hex(),
                            rec.introHint().isEmpty() ? "-" : rec.introHint(),
                            rec.x25519Pub().isEmpty() ? "-" : rec.x25519Pub());
                }
                return 0;
            }
            case "enroll" -> {
                if (args.size() < 4) {
                    inv.err().println("Usage: nodenet enroll NODEID X25519PUB MLKEMPUB [host:port]");
                    return 1;
                }
                NodeId id = new NodeId(args.get(1));
                String x = args.get(2);
                String k = args.get(3);
                String hint = args.size() >= 5 ? args.get(4) : "";
                PeerCapabilities caps = new PeerCapabilities(true, true, Set.of());
                net.enroll(id, caps);
                EnrollmentStore store = ctx.getServiceRegistry().get(EnrollmentStore.class).orElse(null);
                if (store != null) {
                    store.save(new EnrollmentRecord(id, true, true, Set.of(), hint, x, k));
                }
                inv.out().println("enrolled " + id.hex());
                return 0;
            }
            case "revoke" -> {
                if (args.size() < 2) {
                    inv.err().println("Usage: nodenet revoke NODEID");
                    return 1;
                }
                net.revoke(new NodeId(args.get(1)));
                return 0;
            }
            default -> {
                inv.err().println(synopsis());
                return 1;
            }
        }
    }
}
