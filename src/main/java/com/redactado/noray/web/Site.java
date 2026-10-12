package com.redactado.noray.web;

import com.redactado.noray.web.ui.Page;
import com.redactado.noray.web.ui.RequestContext;

import java.util.Optional;

/**
 * A public site: reachable without signing in, so it gets no server-side state (pages render without actions) and every
 * response is sandboxed into an opaque origin (see docs/WEB_UI_DESIGN.md §5.5).
 */
@FunctionalInterface
public interface Site {

    /** Content at {@code path} (relative to the site prefix, starting with "/"), or empty for 404. */
    Optional<Content> get(String path, RequestContext request);

    sealed interface Content permits PageContent, FileContent, Redirect {
    }

    /** A page rendered with the kit (no actions). */
    record PageContent(Page page) implements Content {
    }

    /** A file served as-is with a type from its extension (HTML and scripts run, but sandboxed). */
    record FileContent(String name, byte[] data) implements Content {
    }

    /** A redirect inside the site (e.g. a directory to its trailing-slash form). */
    record Redirect(String location) implements Content {
    }
}
