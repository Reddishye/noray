package com.redactado.noray.kernel.capability;

/** A sandboxed module tried something its grants do not cover. */
public final class CapabilityDeniedException extends SecurityException {
    private final String moduleId;
    private final String capability;

    public CapabilityDeniedException(String moduleId, String capability) {
        super("Module [" + moduleId + "] lacks capability '" + capability + "'");
        this.moduleId = moduleId;
        this.capability = capability;
    }

    public String moduleId() { return moduleId; }
    public String capability() { return capability; }
}
