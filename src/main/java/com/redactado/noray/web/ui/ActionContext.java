package com.redactado.noray.web.ui;

/** What an action can ask for besides re-rendering its own region. */
public interface ActionContext {

    /** Re-render the whole page body. */
    void refreshPage();

    /** Send the browser to another URL (validated like an {@code href}). */
    void navigate(String url);

    /** Show a short notification. */
    void toast(Toast.Level level, String message);
}
