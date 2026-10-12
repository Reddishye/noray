package com.redactado.noray.pkg;

import com.redactado.noray.identity.SigningKey;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Builds signed packages (and real jars compiled on the fly) for the package tests. */
final class TestPackages {

    private TestPackages() {
    }

    static PackageManifest.Draft draft(String id, String version, PackageManifest.Sandboxing sandbox, byte[] payload,
                                       String publisher, List<String> requests) {
        return new PackageManifest.Draft(id, version, PackageManifest.NODE_API, sandbox, "com.example.Main",
                Hex.sha256(payload), payload.length, publisher, requests, List.of(), "");
    }

    static byte[] sign(byte[] manifest, SigningKey... keys) {
        byte[] out = manifest;
        for (SigningKey k : keys) {
            PackageManifest m = PackageManifest.parse(out);
            out = m.withSignature(k.publicKey(), k.sign(PackageManifest.SIGNATURE_DOMAIN, m.body()));
        }
        return out;
    }

    static PackageArchive archive(PackageManifest.Draft d, byte[] payload, SigningKey... keys) {
        return new PackageArchive(sign(d.toBytes(), keys), payload);
    }

    static Publisher publisher(String name, boolean trusted, int threshold, SigningKey... keys) {
        return new Publisher(name, Stream.of(keys).map(SigningKey::publicKey).toList(), threshold, trusted);
    }

    /** Compiles one Java source file against the test classpath and returns the jar bytes. */
    static byte[] compileJar(Path work, String className, String source) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Path src = work.resolve("src").resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Path classes = work.resolve("classes");
        Files.createDirectories(classes);
        int rc = javac.run(null, null, null, "-d", classes.toString(), "-cp", System.getProperty("java.class.path"),
                src.toString());
        if (rc != 0) throw new IllegalStateException("javac failed for " + className);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bos); Stream<Path> files = Files.walk(classes)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                jar.write(Files.readAllBytes(p));
                jar.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    static final class Hex {
        static String sha256(byte[] data) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
