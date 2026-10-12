package org.noray.examples.hosting;

import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.kernel.api.InitLevel;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.kernel.api.KernelModule;
import com.redactado.noray.web.WebApps;
import com.redactado.noray.web.kit.FileBrowser;
import com.redactado.noray.web.kit.StaticSite;
import com.redactado.noray.web.ui.Page;
import com.redactado.noray.web.ui.Routes;
import com.redactado.noray.web.ui.WebApp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.redactado.noray.web.html.Html.*;
import static com.redactado.noray.web.kit.Kit.*;

/**
 * Reference third-party module: serves a folder of the Noray filesystem as a public website and adds a small admin
 * page to the dashboard. It is written only against the public API and runs sandboxed, with nothing but its grants:
 *
 * <pre>
 *   fs.read:/mnt/sites        the content (read-only: the module can never change it)
 *   web.site:/site            the public prefix
 *   web.app                   the admin page under /apps/org.noray.examples.hosting
 *   config.read:hosting.*     its own settings (hosting.root, hosting.prefix)
 * </pre>
 */
public final class StaticHostingModule implements KernelModule {

    public static final String ID = "org.noray.examples.hosting";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public InitLevel getInitLevel() {
        return InitLevel.APPLICATION;
    }

    @Override
    public Set<String> requestedCapabilities() {
        return Set.of("fs.read:/mnt/sites", "web.site:/site", "web.app", "config.read:hosting.*");
    }

    @Override
    public void init(KernelContext ctx) {
        String root = ctx.getConfiguration().get("hosting.root", "/mnt/sites");
        String prefix = ctx.getConfiguration().get("hosting.prefix", "/site");
        FileSystemService fs = ctx.getServiceRegistry().get(FileSystemService.class)
                .orElseThrow(() -> new IllegalStateException("needs fs.read:" + root));
        WebApps web = ctx.getServiceRegistry().get(WebApps.class)
                .orElseThrow(() -> new IllegalStateException("needs web.site:" + prefix + " and web.app"));
        VfsPath rootPath = VfsPath.parse(root);

        WebApps.Mount site = web.mountSite(prefix, new StaticSite(fs, rootPath, prefix));
        WebApps.Mount app = web.mountApp("hosting", new WebApp() {
            @Override
            public String title() {
                return "Static hosting";
            }

            @Override
            public void routes(Routes r) {
                r.nav("Site", "/");
                r.page("/", c -> {
                    FileBrowser files = new FileBrowser(fs, rootPath, "Site content");
                    return Page.of("Static hosting", rr -> {
                        Map<String, Object> facts = new LinkedHashMap<>();
                        facts.put("Public URL", a(href(prefix + "/"), target("_blank"), rel("noopener"), prefix + "/"));
                        facts.put("Content", mono(root));
                        facts.put("Access", "read-only (the module holds fs.read only)");
                        return stack(pageHeader("Static hosting", "A folder of the namespace, served as a public website."),
                                card("Site", keyValues(facts)), rr.live(files));
                    });
                });
            }
        });
        ctx.registerResource(ID, site);
        ctx.registerResource(ID, app);
    }

    @Override
    public void shutdown(KernelContext ctx) {
        // the mounts are resources: the kernel closes them
    }
}
