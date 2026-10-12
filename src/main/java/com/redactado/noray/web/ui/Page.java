package com.redactado.noray.web.ui;

/** A page: its title and the component that renders its body. */
public record Page(String title, Component body) {
    public static Page of(String title, Component body) {
        return new Page(title, body);
    }
}
