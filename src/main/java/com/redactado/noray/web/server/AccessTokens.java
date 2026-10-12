package com.redactado.noray.web.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Access tokens for the web UI. A token is shown once when created; only its SHA-256 is stored
 * ({@code <data>/web/tokens}, mode 0600, one {@code label:role:hash} per line; an older {@code label:hash} line is an
 * operator). Verification compares against every stored hash in constant time.
 */
public final class AccessTokens {

    /** What a signed-in user may do: operators run any action, viewers only view actions. */
    public enum Role { OPERATOR, VIEWER }

    /** Who a token belongs to. */
    public record Grant(String label, Role role) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path file;
    private final Map<String, byte[]> hashes = new LinkedHashMap<>();
    private final Map<String, Role> roles = new LinkedHashMap<>();

    public AccessTokens(Path file) {
        this.file = file;
        load();
    }

    private synchronized void load() {
        hashes.clear();
        roles.clear();
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.strip().split(":");
                if (p.length < 2 || p.length > 3) continue;
                String label = p[0];
                roles.put(label, p.length == 3 ? Role.valueOf(p[1].toUpperCase(Locale.ROOT)) : Role.OPERATOR);
                hashes.put(label, HexFormat.of().parseHex(p[p.length - 1]));
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("cannot read web tokens " + file + ": " + e.getMessage(), e);
        }
    }

    /** Creates an operator token labelled {@code label} and returns it; it cannot be shown again. */
    public synchronized String issue(String label) {
        return issue(label, Role.OPERATOR);
    }

    /** Creates a token with the given role and returns it; it cannot be shown again. */
    public synchronized String issue(String label, Role role) {
        if (!label.matches("[A-Za-z0-9._-]{1,40}")) throw new IllegalArgumentException("label: letters, digits, . _ - (max 40)");
        if (hashes.containsKey(label)) throw new IllegalArgumentException("a token labelled '" + label + "' exists; revoke it first");
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = "nrt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        hashes.put(label, sha256(token));
        roles.put(label, role);
        save();
        return token;
    }

    public synchronized boolean revoke(String label) {
        boolean had = hashes.remove(label) != null;
        roles.remove(label);
        if (had) save();
        return had;
    }

    public synchronized List<String> labels() {
        return new ArrayList<>(hashes.keySet());
    }

    public synchronized Role roleOf(String label) {
        return roles.getOrDefault(label, Role.OPERATOR);
    }

    public synchronized boolean isEmpty() {
        return hashes.isEmpty();
    }

    /** Label and role of the matching token. */
    public synchronized Optional<Grant> authenticate(String token) {
        return verify(token).map(l -> new Grant(l, roleOf(l)));
    }

    /** The label of the matching token. Every stored hash is compared, so timing does not reveal which one matched. */
    public synchronized Optional<String> verify(String token) {
        if (token == null || token.length() > 200) return Optional.empty();
        byte[] h = sha256(token.strip());
        String match = null;
        for (Map.Entry<String, byte[]> e : hashes.entrySet()) {
            if (MessageDigest.isEqual(h, e.getValue()) && match == null) match = e.getKey();
        }
        return Optional.ofNullable(match);
    }

    private void save() {
        StringBuilder sb = new StringBuilder("# web UI access tokens: label:role:sha256 (web token new|revoke)\n");
        hashes.forEach((k, v) -> sb.append(k).append(':').append(roleOf(k).name().toLowerCase(Locale.ROOT)).append(':')
                .append(HexFormat.of().formatHex(v)).append('\n'));
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // not POSIX
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + file, e);
        }
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
