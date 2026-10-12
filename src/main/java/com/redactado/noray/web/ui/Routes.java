package com.redactado.noray.web.ui;

import java.util.function.Function;

/** Where an app declares its pages. Patterns: literal segments, {@code {name}}, and a final {@code {*rest}}. */
public interface Routes {

    Routes page(String pattern, Function<RequestContext, Page> handler);

    Routes download(String pattern, Function<RequestContext, Download> handler);

    /** An entry in the navigation sidebar, pointing to an app path. */
    Routes nav(String label, String appPath);
}
