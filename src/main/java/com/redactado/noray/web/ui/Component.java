package com.redactado.noray.web.ui;

import com.redactado.noray.web.html.Node;

/**
 * A piece of UI. Stateless components are plain functions; stateful ones keep their state in fields, which live on the
 * server for as long as the page is open (nothing is sent to the browser but the rendered HTML).
 */
@FunctionalInterface
public interface Component {
    Node render(Render r);
}
