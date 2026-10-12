package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.web.WebApps;
import com.redactado.noray.web.server.AccessTokens;
import com.redactado.noray.web.server.WebServer;

import java.io.PrintStream;
import java.util.List;
import java.util.Set;

/** The web UI: is it running, which apps are mounted, and the operator access tokens. */
public final class WebCommand implements Command {

    @Override public String name() { return "web"; }
    @Override public String description() { return "Web UI: status, mounted apps, access tokens"; }
    @Override public String synopsis() { return "web status | web token new|list|revoke [LABEL]"; }
    @Override public CommandCategory category() { return CommandCategory.SYSTEM; }
    @Override public Set<String> switchFlags() { return Set.of("viewer"); }

    @Override
    public List<String> complete(KernelContext ctx, List<String> args) {
        if (args.size() <= 1) return List.of("status", "token");
        if (args.size() == 2 && args.getFirst().equals("token")) return List.of("new", "list", "revoke");
        if (args.size() == 3 && args.get(1).equals("revoke")) {
            return ctx.getServiceRegistry().get(AccessTokens.class).map(AccessTokens::labels).orElse(List.of());
        }
        return List.of();
    }

    @Override
    public String longHelp() {
        return """
            The web UI serves the node dashboard and every app modules mount. It starts when noray.web.port is set
            (bound to noray.web.bind, 127.0.0.1 by default; a non-loopback address needs noray.web.tls.keystore).

              web status               address, sessions, mounted apps
              web token new LABEL [--viewer]
                                       create an access token (shown ONCE; only its hash is stored); --viewer can look
                                       and browse but not run anything that changes the node
              web token list           labels and roles of the existing tokens
              web token revoke LABEL   delete a token (sessions created with it end at their idle timeout)
            """;
    }

    @Override
    public int execute(CommandInvocation inv) {
        PrintStream out = inv.out();
        var reg = inv.context().getServiceRegistry();
        var args = inv.args();
        String action = args.isEmpty() ? "status" : args.get(0);
        if (action.equals("status")) {
            var server = reg.get(WebServer.class);
            out.println(server.map(s -> "listening on port " + s.port() + ", " + s.sessionCount() + " session(s)")
                    .orElse("not listening (set noray.web.port)"));
            reg.get(WebApps.class).ifPresent(w -> w.apps().forEach(a ->
                    out.println("  /apps/" + a.slug() + "  " + a.title() + (a.owner().equals("core") ? "" : "  (" + a.owner() + ")"))));
            return 0;
        }
        if (!action.equals("token") || args.size() < 2) {
            out.println(longHelp());
            return 1;
        }
        AccessTokens tokens = reg.get(AccessTokens.class).orElse(null);
        if (tokens == null) {
            inv.err().println("web: the web module is not running");
            return 1;
        }
        try {
            switch (args.get(1)) {
                case "new" -> {
                    String label = args.size() > 2 ? args.get(2) : "operator";
                    AccessTokens.Role role = inv.hasFlag("viewer") ? AccessTokens.Role.VIEWER : AccessTokens.Role.OPERATOR;
                    String t = tokens.issue(label, role);
                    out.println(t);
                    out.println("(" + role.name().toLowerCase() + " token '" + label + "'; store it now, it is not shown again)");
                    return 0;
                }
                case "list" -> {
                    if (tokens.labels().isEmpty()) out.println("(no tokens)");
                    tokens.labels().forEach(l -> out.println(l + "  " + tokens.roleOf(l).name().toLowerCase()));
                    return 0;
                }
                case "revoke" -> {
                    if (args.size() < 3) {
                        out.println("web token revoke LABEL");
                        return 1;
                    }
                    boolean had = tokens.revoke(args.get(2));
                    out.println(had ? "revoked" : "web: no token labelled " + args.get(2));
                    return had ? 0 : 1;
                }
                default -> {
                    out.println(longHelp());
                    return 1;
                }
            }
        } catch (IllegalArgumentException e) {
            inv.err().println("web: " + e.getMessage());
            return 1;
        }
    }
}
