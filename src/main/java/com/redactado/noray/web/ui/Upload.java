package com.redactado.noray.web.ui;

/**
 * A file submitted with a form. {@code filename} is only the last path segment the browser sent, already stripped of
 * directories and control characters; treat it, the declared type and the bytes as untrusted input.
 */
public record Upload(String field, String filename, String declaredType, byte[] data) {

    /** True when the name is safe to use as one path segment (no separators, not "." or "..", not hidden). */
    public boolean hasSafeName() {
        return !filename.isEmpty() && filename.length() <= 255 && !filename.equals(".") && !filename.equals("..")
                && !filename.startsWith(".") && filename.chars().noneMatch(c -> c == '/' || c == '\\' || c < 0x20 || c == 0x7f);
    }
}
