package com.redactado.noray.kernel.capability;

import java.util.*;

/** What one module has been granted. Immutable; empty means nothing. */
public final class CapabilitySet {
    public static final CapabilitySet NONE = new CapabilitySet(List.of());

    private final List<Capability> granted;

    private CapabilitySet(List<Capability> granted) {
        this.granted = List.copyOf(granted);
    }

    public static CapabilitySet of(Capability... caps) { return new CapabilitySet(List.of(caps)); }

    /** Parses a comma or whitespace separated list. Fails on anything it does not understand. */
    public static CapabilitySet parse(String text) {
        if (text == null || text.isBlank()) return NONE;
        List<Capability> out = new ArrayList<>();
        for (String part : text.split("[,\\s]+")) {
            if (!part.isBlank()) out.add(Capability.parse(part));
        }
        return new CapabilitySet(out);
    }

    public boolean allows(Capability wanted) {
        for (Capability c : granted) {
            if (c.covers(wanted)) return true;
        }
        return false;
    }

    public boolean allows(String kind, String arg) { return allows(Capability.of(kind, arg)); }

    /** True when any grant of these kinds exists (used to decide on scoped views). */
    public boolean hasKind(String... kinds) {
        for (Capability c : granted) {
            for (String k : kinds) if (c.kind().equals(k)) return true;
        }
        return false;
    }

    public List<Capability> list() { return granted; }

    public boolean isEmpty() { return granted.isEmpty(); }

    /** Subtree scopes of a given fs kind. */
    public List<String> scopes(String kind) {
        List<String> out = new ArrayList<>();
        for (Capability c : granted) if (c.kind().equals(kind)) out.add(c.arg());
        return out;
    }

    @Override public String toString() {
        StringJoiner j = new StringJoiner(", ");
        granted.forEach(c -> j.add(c.toString()));
        return j.toString();
    }
}
