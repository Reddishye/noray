package com.redactado.noray.web;

import com.redactado.noray.web.ui.WebApp;

import java.util.List;

/**
 * Where modules put their UI. A sandboxed module receives a view bound to its own id: {@code mountApp} always mounts
 * under {@code /apps/<module id>} and needs the {@code web.app} grant; {@code mountSite} needs a {@code web.site:/prefix}
 * grant covering the prefix. The unscoped service (core) can mount any slug and any prefix.
 */
public interface WebApps {

    /** Handle to undo a mount (modules unmount when they shut down; the kernel also cleans up their resources). */
    interface Mount extends AutoCloseable {
        String path();

        @Override
        void close();
    }

    record AppInfo(String slug, String title, String owner) {
    }

    /** Mounts an authenticated app at {@code /apps/<slug>/}. Bound views ignore {@code slug} and use the module id. */
    Mount mountApp(String slug, WebApp app);

    /** Mounts a public site under {@code prefix} ({@code "/"} for the root). */
    Mount mountSite(String prefix, Site site);

    List<AppInfo> apps();
}
