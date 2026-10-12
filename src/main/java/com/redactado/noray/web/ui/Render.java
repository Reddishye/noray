package com.redactado.noray.web.ui;

import com.redactado.noray.web.html.Attr;
import com.redactado.noray.web.html.Node;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * What a component can do while rendering: place live regions, bind actions to elements, build links and blob URLs.
 * Actions are bound to the region being rendered: when one runs, that region is rendered again and patched in the page.
 */
public interface Render {

    /** Renders {@code c} as its own region, re-rendered when one of its actions runs. */
    Node live(Component c);

    /** A region that is also re-rendered and pushed every {@code every} (at least 500 ms) while the page is open. */
    Node live(Component c, Duration every);

    /** Runs {@code action} when the element is clicked. */
    default Attr onClick(Runnable action) {
        return on("click", e -> action.run());
    }

    /**
     * A view-only action: it changes what this page shows (sorting, filtering, switching tabs, opening a folder) and
     * nothing else, so read-only viewers may run it. Every other action needs operator access.
     */
    Attr onView(String event, Consumer<Event> action);

    default Attr onViewClick(Runnable action) {
        return onView("click", e -> action.run());
    }

    /** Runs {@code action} for a DOM event ({@code click}, {@code change}, {@code input}); the event carries the element's value. */
    Attr on(String event, Consumer<Event> action);

    /** Attributes for a {@code <form>}: submitted fields arrive as {@link FormData}. Works without JavaScript too. */
    Attr[] onSubmit(Consumer<Event> action);

    /**
     * Attributes for a {@code <form>} with file inputs: sent as {@code multipart/form-data}; the files arrive in
     * {@link FormData#files()}. Size is limited by {@code noray.web.upload.max.mib}.
     */
    Attr[] onUpload(Consumer<Event> action);

    /** A URL inside the current app ({@code "/files"} → {@code "/apps/fs/files"}). */
    String link(String appPath);

    /** A short-lived URL serving bytes with a safe content type (see {@link Blob}). */
    String blob(Blob blob);

    /** Information about the request that created this page. */
    RequestContext request();
}
