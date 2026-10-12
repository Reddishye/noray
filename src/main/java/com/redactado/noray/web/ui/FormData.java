package com.redactado.noray.web.ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Submitted form fields. Every value is a string as typed; parse and validate it like any untrusted input. */
public final class FormData {

    public static final FormData EMPTY = new FormData(Map.of());

    private final Map<String, List<String>> fields;
    private final List<Upload> files;

    public FormData(Map<String, List<String>> fields) {
        this(fields, List.of());
    }

    public FormData(Map<String, List<String>> fields, List<Upload> files) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        fields.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        this.fields = Collections.unmodifiableMap(copy);
        this.files = List.copyOf(files);
    }

    /** Every uploaded file, in form order. */
    public List<Upload> files() {
        return files;
    }

    /** The files submitted under {@code field}. */
    public List<Upload> files(String field) {
        return files.stream().filter(f -> f.field().equals(field)).toList();
    }

    public Optional<String> get(String name) {
        List<String> v = fields.get(name);
        return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v.getFirst());
    }

    public String get(String name, String fallback) {
        return get(name).orElse(fallback);
    }

    public List<String> all(String name) {
        return fields.getOrDefault(name, List.of());
    }

    public boolean has(String name) {
        return fields.containsKey(name);
    }

    public Map<String, List<String>> asMap() {
        return fields;
    }

    @Override
    public String toString() {
        return fields.keySet().toString(); // values may be secrets
    }
}
