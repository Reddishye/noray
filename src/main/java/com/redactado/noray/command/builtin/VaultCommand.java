package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.filesystem.vfs.journal.NamespaceVault;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Set;

/** Replicas of the namespace: sealed snapshots that certified peers keep so that losing this disk is survivable. */
public final class VaultCommand implements Command {

    @Override public Set<String> switchFlags() { return Set.of("force"); }

    @Override public String name() { return "vault"; }
    @Override public String description() { return "Replicated namespace snapshots (recovery after losing the disk)"; }
    @Override public String synopsis() { return "vault status|push|restore [--force] [--wait SECONDS]"; }
    @Override public CommandCategory category() { return CommandCategory.SYSTEM; }

    @Override
    public String longHelp() {
        return """
            The namespace journal is sealed with a key derived from this node's passphrase and a copy is handed
            to a few certified peers every time it changes. Peers cannot read it; they only keep and return it.

              vault status           which peers confirmed which version, and whose copies this node keeps
              vault push             send a fresh copy now
              vault restore          after losing the data directory: unlock with the same passphrase, join the mesh,
                                     then fetch the newest copy and rewrite the journal (restart the node afterwards)
                                     --force replaces a journal that is not empty; --wait N seconds to collect answers (10)

            Configuration: noray.vault.enabled (true), noray.vault.copies (3), noray.vault.interval.s (60).
            Needs the keyring (passphrase) and the managed identity.
            """;
    }

    @Override
    public int execute(CommandInvocation inv) throws Exception {
        PrintStream out = inv.out();
        NamespaceVault v = inv.context().getServiceRegistry().get(NamespaceVault.class).orElse(null);
        if (v == null) {
            inv.err().println("vault: not active (needs a passphrase, noray.identity.authority.keys and noray.vault.enabled)");
            return 1;
        }
        String action = inv.args().isEmpty() ? "status" : inv.args().get(0);
        switch (action) {
            case "status" -> {
                v.acknowledged().forEach((p, ver) -> out.println("  " + p.hex() + " holds version " + ver));
                if (v.acknowledged().isEmpty()) out.println("  no peer has confirmed a copy yet");
                v.keptFor().forEach(p -> out.println("  we keep a copy for " + p.hex()));
                return 0;
            }
            case "push" -> {
                int n = v.push(true);
                out.println("sent to " + n + " peer(s)");
                return n > 0 ? 0 : 1;
            }
            case "restore" -> {
                int secs = Integer.parseInt(inv.getFlag("wait", "10"));
                try {
                    int n = v.restore(inv.booleanFlags().contains("force"), Duration.ofSeconds(secs));
                    out.println("restored " + n + " records into the journal. Restart the node to load them.");
                    return 0;
                } catch (IllegalStateException e) {
                    out.println("vault: " + e.getMessage());
                    return 1;
                }
            }
            default -> {
                out.println(longHelp());
                return 1;
            }
        }
    }
}
