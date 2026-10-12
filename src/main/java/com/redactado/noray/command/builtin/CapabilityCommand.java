package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.kernel.capability.CapabilityRegistry;
import com.redactado.noray.kernel.capability.CapabilitySet;

import java.io.PrintStream;
import java.time.Instant;

/** What sandboxed modules may do, and what they were refused. */
public final class CapabilityCommand implements Command {

    @Override public String name() { return "capability"; }
    @Override public String description() { return "Show what sandboxed modules may do and what was refused"; }
    @Override public String synopsis() { return "capability list|show MODULE|audit [N]|check GRANTS"; }
    @Override public CommandCategory category() { return CommandCategory.SYSTEM; }

    @Override
    public String longHelp() {
        return """
            Modules that are not part of the core run sandboxed: they only see what the operator
            grants in noray.module.<id>.grants (comma separated), and nothing by default.

              capability list           sandboxed modules and their grants
              capability show MODULE    the grants of one module
              capability audit [N]      the last N refusals (default 20)
              capability check GRANTS   validate a grants string before putting it in the configuration

            Grants: service:Name | service:* | service.register:Name | fs.read:/path | fs.write:/path |
            config.read:key* | config.write:key* | event.publish:Name | event.subscribe:Name |
            executor | kernel.modules. A wildcard never covers keys, secrets or raw storage services.
            """;
    }

    @Override
    public int execute(CommandInvocation inv) {
        PrintStream out = inv.out();
        var args = inv.args();
        if (args.isEmpty()) {
            out.println(longHelp());
            return 1;
        }
        if (args.get(0).equals("check")) {
            if (args.size() < 2) {
                inv.err().println("capability check GRANTS");
                return 1;
            }
            try {
                CapabilitySet set = CapabilitySet.parse(String.join(" ", args.subList(1, args.size())));
                out.println("OK: " + (set.isEmpty() ? "(nothing)" : set));
                return 0;
            } catch (IllegalArgumentException e) {
                inv.err().println("Invalid: " + e.getMessage());
                return 1;
            }
        }
        CapabilityRegistry reg = inv.context().getServiceRegistry().get(CapabilityRegistry.class).orElse(null);
        if (reg == null) {
            inv.err().println("Capabilities are not available.");
            return 1;
        }
        switch (args.get(0)) {
            case "list" -> {
                if (reg.sandboxedModules().isEmpty()) {
                    out.println("No sandboxed modules.");
                }
                for (String m : reg.sandboxedModules()) {
                    CapabilitySet g = reg.grantsOf(m).orElse(CapabilitySet.NONE);
                    out.println(m + ": " + (g.isEmpty() ? "(nothing)" : g));
                }
                return 0;
            }
            case "show" -> {
                if (args.size() < 2) {
                    inv.err().println("capability show MODULE");
                    return 1;
                }
                var g = reg.grantsOf(args.get(1));
                if (g.isEmpty()) {
                    inv.err().println("Not a sandboxed module: " + args.get(1));
                    return 1;
                }
                if (g.get().isEmpty()) out.println("(nothing)");
                g.get().list().forEach(c -> out.println(c));
                return 0;
            }
            case "audit" -> {
                int n = args.size() > 1 ? Integer.parseInt(args.get(1)) : 20;
                out.println("Refusals so far: " + reg.denialCount());
                for (var d : reg.recentDenials(n)) {
                    out.println(Instant.ofEpochMilli(d.timeMillis()) + "  " + d.moduleId() + "  " + d.capability());
                }
                return 0;
            }
            default -> {
                inv.err().println("Unknown subcommand: " + args.get(0));
                return 1;
            }
        }
    }
}
