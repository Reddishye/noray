package com.redactado.noray.web.ui;

import java.util.Map;
import java.util.Optional;

/** The request that opened a page: route parameters, query parameters, and who is looking. */
public interface RequestContext {

    /** A path parameter of the route ({@code {name}} or {@code {*rest}}); empty string if absent. */
    String param(String name);

    Optional<String> query(String name);

    Map<String, String> params();

    /** The request path, decoded and normalised. */
    String path();

    /** Who is signed in ({@code "operator"}), or empty for a public site. */
    Optional<String> user();

    /** A URL inside the current app. */
    String link(String appPath);
}
