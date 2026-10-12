package com.redactado.noray.web.ui;

/** A transient notification shown by the runtime. */
public record Toast(Level level, String message) {
    public enum Level { INFO, SUCCESS, WARNING, ERROR }
}
