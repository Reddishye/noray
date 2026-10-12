package com.redactado.noray.web.ui;

/**
 * An authenticated app mounted under {@code /apps/<slug>/}. It declares its pages and navigation entries; it never sees
 * raw HTTP.
 *
 * <pre>{@code
 * public final class Notes implements WebApp {
 *     public String title() { return "Notes"; }
 *     public void routes(Routes r) {
 *         r.nav("All notes", "/");
 *         r.page("/", ctx -> Page.of("Notes", new NoteList(store)));
 *         r.page("/note/{id}", ctx -> Page.of("Note", new NoteView(store, ctx.param("id"))));
 *     }
 * }
 * }</pre>
 */
public interface WebApp {

    String title();

    void routes(Routes routes);
}
