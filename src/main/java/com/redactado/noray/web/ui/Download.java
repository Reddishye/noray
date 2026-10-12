package com.redactado.noray.web.ui;

/** A response that is a file to save. Always sent as an attachment with a sanitized name. */
public record Download(String filename, byte[] data) {
}
