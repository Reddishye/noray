package com.redactado.noray.kernel.api;

import java.util.Set;

/**
 * Kernel configuration abstraction.
 */
public interface Configuration {
    /**
     * Gets a configuration value by key with a default.
     *
     * @param key the configuration key
     * @param defaultValue the default value
     * @return the value or default
     */
    String get(String key, String defaultValue);

    /**
     * Gets an integer configuration value by key with a default.
     *
     * @param key the configuration key
     * @param defaultValue the default value
     * @return the value or default
     */
    int getInt(String key, int defaultValue);

    /**
     * Gets a boolean configuration value by key with a default.
     *
     * @param key the configuration key
     * @param defaultValue the default value
     * @return the value or default
     */
    boolean getBoolean(String key, boolean defaultValue);

    /**
     * Sets a configuration value.
     *
     * @param key the configuration key
     * @param value the value to set
     */
    void set(String key, String value);

    /**
     * Returns all configuration keys.
     *
     * @return a set of all configuration keys
     */
    Set<String> getAllKeys();
}