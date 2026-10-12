package com.redactado.noray;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Where a node's settings come from, lowest priority first: built-in defaults, a properties file
 * ({@code --config PATH}, else {@code $NORAY_CONFIG}, else {@code ./noray.properties}, else
 * {@code /etc/noray/noray.properties}), {@code -Dnoray.*} system properties, then {@code --set key=value} arguments.
 * Secrets are better kept out of all of these: {@code noray.keyring.passphrase.file} and {@code NORAY_PASSPHRASE} exist.
 */
public final class NorayConfig {

    private NorayConfig() {
    }

    public static Map<String, String> load(String[] args) {
        return load(args, System.getenv(), System.getProperties());
    }

    static Map<String, String> load(String[] args, Map<String, String> env, Properties sysProps) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("noray.env", "production");
        out.put("noray.max.threads", "100");

        String file = null;
        Map<String, String> sets = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                file = args[++i];
            } else if (args[i].startsWith("--config=")) {
                file = args[i].substring("--config=".length());
            } else if ("--set".equals(args[i]) && i + 1 < args.length) {
                put(sets, args[++i]);
            }
        }
        Path path = locate(file, env);
        if (path != null) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(path)) {
                p.load(r);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read configuration " + path + ": " + e.getMessage(), e);
            }
            for (String k : p.stringPropertyNames()) out.put(k, p.getProperty(k).trim());
        }
        for (String k : sysProps.stringPropertyNames()) {
            if (k.startsWith("noray.") && !k.equals("noray.daemon") && !k.equals("noray.config")) {
                out.put(k, sysProps.getProperty(k));
            }
        }
        out.putAll(sets);
        return out;
    }

    private static void put(Map<String, String> into, String assignment) {
        int eq = assignment.indexOf('=');
        if (eq <= 0) throw new IllegalArgumentException("--set expects key=value, got: " + assignment);
        into.put(assignment.substring(0, eq).trim(), assignment.substring(eq + 1).trim());
    }

    private static Path locate(String explicit, Map<String, String> env) {
        if (explicit != null) {
            Path p = Path.of(explicit);
            if (!Files.isRegularFile(p)) throw new IllegalStateException("configuration file not found: " + p);
            return p;
        }
        String fromEnv = env.get("NORAY_CONFIG");
        if (fromEnv != null && !fromEnv.isBlank()) {
            Path p = Path.of(fromEnv);
            if (!Files.isRegularFile(p)) throw new IllegalStateException("NORAY_CONFIG points to nothing: " + p);
            return p;
        }
        for (String candidate : new String[]{"noray.properties", "/etc/noray/noray.properties"}) {
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }
}
