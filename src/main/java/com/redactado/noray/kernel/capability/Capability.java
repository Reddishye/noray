package com.redactado.noray.kernel.capability;

import java.util.Locale;
import java.util.Set;

/**
 * One permission, written {@code kind[:argument]}.
 *
 * <pre>
 *   service:FileSystemService     use a registered service by simple class name
 *   service:*                     any service that is not sensitive
 *   service.register:MyService    register a NEW service (never replace an existing one)
 *   fs.read:/mnt/repo             read the subtree (scoped FileSystemService)
 *   fs.write:/mnt/repo            read and write the subtree (create, overwrite, delete)
 *   fs.create:/mnt/inbox          create files and directories and fill them once; no overwrite, delete or read
 *   fs.delete:/mnt/inbox          delete entries of the subtree (nothing else)
 *   config.read:noray.fs.*        read configuration keys (trailing * = prefix)
 *   config.write:app.mymodule.*   change configuration keys
 *   event.publish:Name | *        publish events by simple class name
 *   event.subscribe:Name | *      listen to events
 *   executor                      use the shared worker pool
 *   kernel.modules                list loaded modules
 * </pre>
 *
 * A wildcard never covers something sensitive (keyring, node keys, raw
 * storage, secrets in configuration): that needs the exact name.
 */
public record Capability(String kind, String arg) {

    private static final Set<String> KINDS = Set.of(
            "service", "service.register", "fs.read", "fs.write", "fs.create", "fs.delete",
            "config.read", "config.write", "event.publish", "event.subscribe",
            "executor", "kernel.modules", "web.app", "web.site");
    private static final Set<String> NO_ARG = Set.of("executor", "kernel.modules", "web.app");

    /** Services that expose keys or bypass the namespace: only by exact name. */
    static final Set<String> SENSITIVE_SERVICES = Set.of(
            "Keyring", "StaticKeys", "IdentityService", "DataEngine", "DataCluster",
            "ObjectStore", "BlobResponder", "VolumeManager", "StorageAdmin", "NamespaceAdmin",
            "FsConfigService", "VolumeHealthMonitor", "NamespaceVault", "MeshTransport", "KernelContext", "CapabilityRegistry",
            "MeshOnionTransport", "OnionService", "MaintenanceService", "PackageManager", "PackageRuntime", "PackageExchange", "WebApps", "WebServer", "AccessTokens");

    public Capability {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("Unknown capability kind: " + kind);
        }
        if (NO_ARG.contains(kind)) {
            if (!arg.isEmpty()) throw new IllegalArgumentException(kind + " takes no argument");
        } else if (arg.isEmpty()) {
            throw new IllegalArgumentException(kind + " needs an argument");
        }
        if ((kind.startsWith("fs.") || kind.equals("web.site")) && !arg.startsWith("/")) {
            throw new IllegalArgumentException(kind + " paths must be absolute: " + arg);
        }
    }

    public static Capability parse(String text) {
        String t = text.trim();
        int i = t.indexOf(':');
        String kind = (i < 0 ? t : t.substring(0, i)).toLowerCase(Locale.ROOT);
        String arg = i < 0 ? "" : t.substring(i + 1).trim();
        return new Capability(kind, arg);
    }

    public static Capability of(String kind, String arg) { return new Capability(kind, arg); }

    /** True when this granted capability covers {@code wanted}. */
    public boolean covers(Capability wanted) {
        if (kind.equals(wanted.kind)) return coversArg(wanted.arg, wanted.kind);
        // writing a subtree implies everything else on it
        if (kind.equals("fs.write") && (wanted.kind.equals("fs.read") || wanted.kind.equals("fs.create")
                || wanted.kind.equals("fs.delete"))) {
            return coversArg(wanted.arg, wanted.kind);
        }
        return false;
    }

    private boolean coversArg(String wantedArg, String wantedKind) {
        if (NO_ARG.contains(kind)) return true;
        if (kind.startsWith("fs.") || kind.equals("web.site")) return pathCovers(arg, wantedArg);
        if (arg.equals(wantedArg)) return true;
        if (!arg.endsWith("*")) return false;
        if (isSensitive(wantedKind, wantedArg)) return false;
        return wantedArg.startsWith(arg.substring(0, arg.length() - 1));
    }

    static boolean pathCovers(String scope, String path) {
        if (scope.equals("/")) return true;
        String s = scope.endsWith("/") ? scope.substring(0, scope.length() - 1) : scope;
        return path.equals(s) || path.startsWith(s + "/");
    }

    static boolean isSensitive(String kind, String arg) {
        return switch (kind) {
            case "service", "service.register" -> SENSITIVE_SERVICES.contains(arg);
            case "config.read", "config.write" -> isSensitiveKey(arg);
            default -> false;
        };
    }

    public static boolean isSensitiveKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("passphrase") || k.contains("password") || k.contains("secret")
                || k.contains("psk") || k.contains("private") || k.contains("token")
                || k.contains("seed") || k.contains("authority");
    }

    @Override public String toString() { return arg.isEmpty() ? kind : kind + ":" + arg; }
}
