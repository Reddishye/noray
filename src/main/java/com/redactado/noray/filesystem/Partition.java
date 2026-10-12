package com.redactado.noray.filesystem;

import java.util.UUID;

public interface Partition {
    UUID uuid();
    Metadata metadata();
}
