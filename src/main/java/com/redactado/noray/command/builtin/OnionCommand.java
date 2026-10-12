package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.net.onion.MeshOnionTransport;
import com.redactado.noray.net.onion.OnionService;

import java.io.PrintStream;
import java.util.HexFormat;

/** Anonymous onion messages: which relays this node knows and what its onion layer has done. */
public final class OnionCommand implements Command {

    @Override public String name() { return "onion"; }
    @Override public String description() { return "Onion routing status (relays known, cell counters)"; }
    @Override public String synopsis() { return "onion status|relays|announce"; }
    @Override public CommandCategory category() { return CommandCategory.NETWORK; }

    @Override
    public String longHelp() {
        return """
            Onion cells carry anonymous one-way messages over the mesh (noray.net.onion.enabled=true).

              onion status     this node's onion key, number of relays known, and the cell counters
              onion relays     the node ids whose onion keys this node has learned
              onion announce   announce this node's onion key to its neighbours now
            """;
    }

    @Override
    public int execute(CommandInvocation inv) {
        PrintStream out = inv.out();
        var registry = inv.context().getServiceRegistry();
        OnionService onion = registry.get(OnionService.class).orElse(null);
        MeshOnionTransport carrier = registry.get(MeshOnionTransport.class).orElse(null);
        if (onion == null || carrier == null) {
            inv.err().println("onion: not enabled (noray.net.onion.enabled=true)");
            return 1;
        }
        String action = inv.args().isEmpty() ? "status" : inv.args().get(0);
        switch (action) {
            case "status" -> {
                out.println("onion key     " + HexFormat.of().formatHex(onion.publicKey()));
                out.println("relays known  " + carrier.relays().size());
                onion.stats().forEach((k, v) -> out.printf("%-14s%d%n", k, v));
                return 0;
            }
            case "relays" -> {
                carrier.relays().forEach(n -> out.println(n.hex()));
                return 0;
            }
            case "announce" -> {
                carrier.announce();
                out.println("announced");
                return 0;
            }
            default -> {
                inv.err().println("onion: unknown action '" + action + "' (status|relays|announce)");
                return 2;
            }
        }
    }
}
