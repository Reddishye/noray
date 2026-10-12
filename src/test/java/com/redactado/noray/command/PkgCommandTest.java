package com.redactado.noray.command;

import com.redactado.noray.command.builtin.PkgCommand;
import com.redactado.noray.kernel.api.*;
import com.redactado.noray.kernel.core.DefaultServiceRegistry;
import com.redactado.noray.pkg.PackageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The publisher and operator workflow through the real shell parser: keygen, build, sign, pin, install, approve. */
class PkgCommandTest {

    @TempDir Path tmp;

    private final DefaultServiceRegistry services = new DefaultServiceRegistry();
    private final CommandRegistry registry = new CommandRegistry();
    private int code;

    private String sh(String line) {
        KernelContext ctx = new KernelContext() {
            @Override public KernelPhase getPhase() { return null; }
            @Override public ServiceRegistry getServiceRegistry() { return services; }
            @Override public EventBus getEventBus() { return null; }
            @Override public ExecutorService getExecutionEngine() { return null; }
            @Override public Configuration getConfiguration() { return null; }
            @Override public void registerResource(String m, AutoCloseable r) { }
            @Override public List<KernelModule> getActiveModules() { return List.of(); }
            @Override public Set<String> getActiveModuleNames() { return Set.of(); }
        };
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
        code = registry.dispatch(line, ctx, System.in, ps, ps);
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void fromKeyToActivePackage() throws Exception {
        registry.register(new PkgCommand());
        services.register(PackageManager.class, new PackageManager(tmp.resolve("packages")));
        Path jar = tmp.resolve("app.jar");
        Files.write(jar, "not really a jar".getBytes(StandardCharsets.UTF_8));
        Path key = tmp.resolve("publisher.key");
        Path pkg = tmp.resolve("app.nrpkg");

        String out = sh("pkg keygen " + key + " --no-passphrase");
        assertEquals(0, code, out);
        Matcher m = Pattern.compile("public key: ([0-9a-f]{64})").matcher(out);
        assertTrue(m.find(), out);
        String pub = m.group(1);

        out = sh("pkg build --id com.example.app --version 1.0.0 --main com.example.Main --payload " + jar
                + " --publisher example --requests fs.write:/mnt/pool/app --description 'An app' --out " + pkg);
        assertEquals(0, code, out);
        out = sh("pkg install " + pkg);
        assertEquals(1, code, "unsigned and unpinned: " + out);
        assertTrue(out.contains("unpinned publisher"), out);

        sh("pkg sign " + pkg + " --key " + key);
        assertEquals(0, code);
        out = sh("pkg inspect " + pkg);
        assertTrue(out.contains("description=An app") && out.contains("signature=" + pub), out);

        out = sh("pkg publisher add example " + pub);
        assertEquals(0, code, out);
        out = sh("pkg install " + pkg);
        assertEquals(0, code, out);
        assertTrue(out.contains("requests: fs.write:/mnt/pool/app") && out.contains("not running"), out);

        out = sh("pkg approve com.example.app 1.0.0 --grants fs.read:/mnt/pool/app");
        assertEquals(0, code, out);
        out = sh("pkg list");
        assertTrue(out.contains("com.example.app") && out.contains("ACTIVE"), out);
        out = sh("pkg show com.example.app");
        assertTrue(out.contains("approved   fs.read:/mnt/pool/app"), out);
        out = sh("pkg verify com.example.app");
        assertEquals(0, code, out);
        out = sh("pkg audit --verify");
        assertEquals(0, code, out);
        assertTrue(out.contains("intact"), out);
    }
}
