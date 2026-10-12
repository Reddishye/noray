package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.ops.PanicWipe;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Set;

/** Emergency: destroy this node's keys (and optionally all its local data), then stop. */
public final class PanicCommand implements Command {

    private final Runnable shutdown;

    public PanicCommand(Runnable shutdown) {
        this.shutdown = shutdown;
    }

    @Override public String name() { return "panic"; }
    @Override public String description() { return "Emergency wipe of this node's keys (crypto-shredding), then stop"; }
    @Override public String synopsis() { return "panic wipe --confirm NODEID-PREFIX [--everything]"; }
    @Override public CommandCategory category() { return CommandCategory.SYSTEM; }
    @Override public Set<String> switchFlags() { return Set.of("everything"); }

    @Override
    public String longHelp() {
        return """
            panic wipe --confirm NODEID-PREFIX [--everything]

            Overwrites and deletes the keyring, node keys, identity, web tokens and namespace journal of THIS node, then
            stops it. Without the keyring the namespace, the file keys it held and its vault copies on other nodes cannot
            be decrypted again, even with the passphrase. --everything also shreds local blob stores and packages.
            The first 8+ characters of this node's id must be given to --confirm, so it cannot be run by accident.

            Limits: overwriting does not reach old blocks on copy-on-write filesystems (btrfs, ZFS) or SSDs; use full-disk
            encryption as well. A passphrase file outside the data directory must be destroyed separately.
            There is NO undo. Backups (backup export) are not touched.
            """;
    }

    @Override
    public int execute(CommandInvocation inv) {
        PrintStream out = inv.out();
        if (inv.args().isEmpty() || !inv.args().getFirst().equals("wipe")) {
            out.println(longHelp());
            return 1;
        }
        String node = inv.context().getServiceRegistry().get(MeshTransport.class).map(m -> m.localId().hex()).orElse("");
        String confirm = inv.getFlag("confirm", "");
        if (node.isEmpty() || confirm.length() < 8 || !node.startsWith(confirm)) {
            out.println("panic: refusing. Confirm with --confirm followed by at least the first 8 characters of this node's id"
                    + (node.isEmpty() ? " (the network module is not running, so the id is unknown)" : ""));
            return 1;
        }
        Path dataDir = Path.of(inv.context().getConfiguration().get("noray.data.dir", "data"));
        PanicWipe.Report r = PanicWipe.wipe(dataDir, inv.hasFlag("everything"));
        out.println("shredded " + r.shredded().size() + " file(s)");
        r.failures().forEach(f -> out.println("FAILED: " + f));
        out.println("stopping the node");
        shutdown.run();
        return r.failures().isEmpty() ? 0 : 1;
    }
}
