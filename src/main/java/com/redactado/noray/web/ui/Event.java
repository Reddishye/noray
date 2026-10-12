package com.redactado.noray.web.ui;

/** A browser event delivered to an action: its type, the element's value (inputs) and the submitted fields (forms). */
public record Event(String type, String value, FormData form, ActionContext ctx) {
}
