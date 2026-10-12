package com.redactado.noray.sandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What to run: the classes of the program (jars or directories, made visible read-only) and the class that
 * implements {@link SandboxedProgram}. The node's own classes needed by the bridge are added automatically.
 */
public record SandboxSpec(List<Path> classpath, String programClass, List<String> args, SandboxProfile profile) {

    public SandboxSpec {
        Objects.requireNonNull(programClass, "programClass");
        classpath = List.copyOf(classpath);
        args = List.copyOf(args);
        Objects.requireNonNull(profile, "profile");
        for (Path p : classpath) {
            if (!p.isAbsolute()) {
                throw new IllegalArgumentException("classpath entries must be absolute: " + p);
            }
        }
    }

    public static SandboxSpec of(Path classpath, String programClass, String... args) {
        return new SandboxSpec(List.of(classpath), programClass, List.of(args), SandboxProfile.defaults());
    }

    public SandboxSpec with(SandboxProfile profile) {
        return new SandboxSpec(classpath, programClass, args, profile);
    }
}
