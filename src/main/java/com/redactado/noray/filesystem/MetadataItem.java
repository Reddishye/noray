package com.redactado.noray.filesystem;

public interface MetadataItem {
    String key();
    String value();
    Class<?> type();
}
