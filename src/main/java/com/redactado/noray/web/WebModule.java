package com.redactado.noray.web;

import com.redactado.noray.kernel.api.InitLevel;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.kernel.api.KernelModule;
import com.redactado.noray.kernel.capability.CapabilityRegistry;
import com.redactado.noray.kernel.capability.CapabilitySet;
import com.redactado.noray.kernel.capability.ServiceAttenuator;
import com.redactado.noray.web.dashboard.MetricsHistory;
import com.redactado.noray.web.dashboard.NodeDashboard;
import com.redactado.noray.web.server.AccessTokens;
import com.redactado.noray.web.server.Mounts;
import com.redactado.noray.web.server.WebServer;
import com.redactado.noray.web.ui.Theme;

import java.nio.file.Path;

/**
 * The web UI module (docs/WEB_UI_DESIGN.md). Always registers {@link WebApps} (so modules can mount UI) and the node
 * dashboard; starts the HTTP server only when {@code noray.web.port} is set. Sandboxed modules get a {@link WebApps}
 * bound to their id through the {@code web.app} / {@code web.site:/prefix} grants.
 */
public final class WebModule implements KernelModule {

    private WebServer server;
    private MetricsHistory history;

    @Override
    public String id() {
        return "web";
    }

    @Override
    public InitLevel getInitLevel() {
        return InitLevel.CORE; // before any module that wants to mount UI
    }

    @Override
    public int getOrderWithinLevel() {
        return 900;
    }

    @Override
    public void init(KernelContext context) throws Exception {
        var cfg = context.getConfiguration();
        Mounts mounts = new Mounts();
        context.getServiceRegistry().register(WebApps.class, mounts);
        context.getServiceRegistry().get(CapabilityRegistry.class).ifPresent(r -> r.registerAttenuator(new ServiceAttenuator<WebApps>() {
            @Override public Class<WebApps> serviceType() { return WebApps.class; }
            @Override public WebApps attenuate(WebApps base, String moduleId, CapabilitySet grants, CapabilityRegistry registry) {
                if (!grants.hasKind("web.app", "web.site")) return null;
                return mounts.bound(moduleId, grants.allows("web.app", ""), grants.scopes("web.site"));
            }
        }));
        AccessTokens tokens = new AccessTokens(Path.of(cfg.get("noray.data.dir", "data")).resolve("web").resolve("tokens"));
        context.getServiceRegistry().register(AccessTokens.class, tokens);

        history = new MetricsHistory();
        context.registerResource(id(), history);
        mounts.mountApp("node", new NodeDashboard(context, history));

        WebServer.Config config = WebServer.fromSettings(cfg::get);
        if (config.port() > 0) {
            server = new WebServer(config, mounts, tokens, Theme.named(cfg.get("noray.web.theme", "noir")));
            context.getServiceRegistry().register(WebServer.class, server);
            context.registerResource(id(), server);
        }
    }

    @Override
    public void shutdown(KernelContext context) {
        if (server != null) server.close();
        if (history != null) history.close();
    }
}
