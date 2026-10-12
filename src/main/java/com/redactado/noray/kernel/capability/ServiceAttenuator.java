package com.redactado.noray.kernel.capability;

/**
 * Turns a powerful service into a narrower view for a sandboxed module
 * (for example the whole filesystem into one subtree).
 */
public interface ServiceAttenuator<T> {
    Class<T> serviceType();

    /** The narrowed service, or null when the grants hold nothing relevant. */
    T attenuate(T base, String moduleId, CapabilitySet grants, CapabilityRegistry registry);
}
