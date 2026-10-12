package com.redactado.noray.pkg;

import com.redactado.noray.kernel.capability.Capability;
import com.redactado.noray.pkg.PackageException.Reason;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The signed description of a package. The text format is deliberately rigid so that what the operator reviews is exactly
 * what was signed and what the node enforces:
 *
 * <pre>
 * noray-package=1
 * id=com.example.hosting
 * version=1.2.0
 * noray.api=1
 * sandbox=process
 * main=com.example.hosting.Main
 * payload.sha256=&lt;64 hex&gt;
 * payload.size=123456
 * publisher=example
 * requests=fs.read:/mnt/sites,fs.write:/mnt/sites/cache      (optional)
 * depends=org.example.lib@&lt;64 hex&gt;                          (optional)
 * description=Static web hosting                              (optional)
 * signature=&lt;public key hex&gt;:&lt;signature hex&gt;                 (one per signing key, last)
 * </pre>
 *
 * Rules (each one refuses the whole manifest): strict UTF-8, at most 64 KiB, LF line ends and a final LF, no blank lines,
 * no control or bidirectional-formatting characters, the magic first line, only the keys above, no key twice, every
 * required key present, signature lines only at the end. Each signature covers every byte before the first signature line
 * under the domain {@value #SIGNATURE_DOMAIN}, so nothing can be added to a signed manifest without breaking it.
 */
public final class PackageManifest {

    /** The module API level this node provides; a package built for a higher level is refused. */
    public static final int NODE_API = 1;
    /** The lowest API level this node still loads. */
    public static final int MIN_API = 1;

    public static final String SIGNATURE_DOMAIN = "noray.package.v1";
    public static final int MAX_BYTES = 64 * 1024;
    public static final long MAX_PAYLOAD = 512L * 1024 * 1024;
    static final int MAX_SIGNATURES = 16;

    private static final String MAGIC = "noray-package=1";
    private static final List<String> ORDER = List.of("id", "version", "noray.api", "sandbox", "main", "payload.sha256",
            "payload.size", "publisher", "requests", "depends", "description");
    private static final Set<String> REQUIRED = Set.of("id", "version", "noray.api", "sandbox", "main", "payload.sha256",
            "payload.size", "publisher");

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");
    private static final Pattern CLASS = Pattern.compile("([A-Za-z_$][A-Za-z\\d_$]*\\.)*[A-Za-z_$][A-Za-z\\d_$]*");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PUBLISHER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z0-9]+)*");
    private static final Pattern SIGNATURE = Pattern.compile("([0-9a-f]{64}):([0-9a-f]{128})");

    /** How the package runs. */
    public enum Sandboxing {
        /** In a child JVM inside the process sandbox; it only sees the filesystem grants. The default for packages. */
        PROCESS,
        /** As a sandboxed module inside the node's JVM; only for publishers the operator marked trusted. */
        JVM;

        static Sandboxing parse(String s) {
            return switch (s) {
                case "process" -> PROCESS;
                case "jvm" -> JVM;
                default -> throw malformed("sandbox must be process or jvm: " + s);
            };
        }

        public String text() {
            return name().toLowerCase();
        }
    }

    /** An exact dependency: another package identified by id and payload hash. */
    public record Dependency(String id, String payloadSha256) {
        @Override public String toString() { return id + "@" + payloadSha256; }
    }

    /** One signing key's signature over the manifest body. */
    public record Signature(byte[] key, byte[] signature) {
        public String keyHex() { return HexFormat.of().formatHex(key); }
    }

    private final String id;
    private final Version version;
    private final int api;
    private final Sandboxing sandbox;
    private final String main;
    private final String payloadSha256;
    private final long payloadSize;
    private final String publisher;
    private final List<String> requests;
    private final List<Dependency> depends;
    private final String description;
    private final byte[] body;
    private final List<Signature> signatures;

    private PackageManifest(Map<String, String> f, byte[] body, List<Signature> signatures) {
        this.id = f.get("id");
        if (!ID.matcher(id).matches() || id.length() > 128 || id.startsWith("com.redactado.noray") || id.startsWith("noray.")) {
            throw malformed("id must be a reverse-DNS name like com.example.tool (not in the noray namespace): " + id);
        }
        this.version = Version.parse(f.get("version"));
        this.api = parseInt(f.get("noray.api"), "noray.api", 1, 999);
        this.sandbox = Sandboxing.parse(f.get("sandbox"));
        this.main = f.get("main");
        if (!CLASS.matcher(main).matches() || main.length() > 256) {
            throw malformed("main is not a class name: " + main);
        }
        this.payloadSha256 = f.get("payload.sha256");
        if (!HEX64.matcher(payloadSha256).matches()) {
            throw malformed("payload.sha256 must be 64 lower-case hex characters");
        }
        this.payloadSize = parseLong(f.get("payload.size"), "payload.size", 0, MAX_PAYLOAD);
        this.publisher = f.get("publisher");
        if (!PUBLISHER.matcher(publisher).matches()) {
            throw malformed("publisher must be a short lower-case name: " + publisher);
        }
        this.requests = parseRequests(f.getOrDefault("requests", ""));
        this.depends = parseDepends(f.getOrDefault("depends", ""));
        this.description = f.getOrDefault("description", "");
        if (description.length() > 300) {
            throw malformed("description longer than 300 characters");
        }
        this.body = body;
        this.signatures = List.copyOf(signatures);
    }

    // ── parsing ───────────────────────────────────────────────────────────────────────────────────────────────────────

    public static PackageManifest parse(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_BYTES) {
            throw malformed("manifest larger than " + MAX_BYTES + " bytes");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw malformed("not valid UTF-8");
        }
        if (text.isEmpty() || text.charAt(text.length() - 1) != '\n') {
            throw malformed("must end with a newline");
        }
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        if (!lines[0].equals(MAGIC)) {
            throw malformed("first line must be '" + MAGIC + "'");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        List<Signature> signatures = new ArrayList<>();
        int bodyEnd = -1; // index of the first signature line
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            checkCharacters(line, i + 1);
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw malformed("line " + (i + 1) + " is not key=value");
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if (key.equals("signature")) {
                if (bodyEnd < 0) bodyEnd = i;
                signatures.add(parseSignature(value, signatures));
                continue;
            }
            if (bodyEnd >= 0) {
                throw malformed("line " + (i + 1) + ": nothing may follow the signatures");
            }
            if (!KEY.matcher(key).matches() || !ORDER.contains(key)) {
                throw malformed("unknown key '" + key + "'");
            }
            if (fields.putIfAbsent(key, value) != null) {
                throw malformed("key '" + key + "' appears twice");
            }
        }
        for (String k : REQUIRED) {
            if (!fields.containsKey(k)) {
                throw malformed("missing key '" + k + "'");
            }
        }
        byte[] body;
        if (bodyEnd < 0) {
            body = bytes.clone();
        } else {
            int offset = 0;
            for (int i = 0; i < bodyEnd; i++) {
                offset += lines[i].getBytes(StandardCharsets.UTF_8).length + 1;
            }
            body = Arrays.copyOf(bytes, offset);
        }
        return new PackageManifest(fields, body, signatures);
    }

    private static void checkCharacters(String line, int number) {
        if (line.isEmpty()) {
            throw malformed("line " + number + " is empty");
        }
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            // control characters hide text in a terminal; bidi/format characters make the reviewed text differ from the bytes
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) {
                throw malformed("line " + number + " contains a control or formatting character (U+"
                        + String.format("%04X", (int) c) + ")");
            }
        }
    }

    private static Signature parseSignature(String value, List<Signature> previous) {
        var m = SIGNATURE.matcher(value);
        if (!m.matches()) {
            throw malformed("signature must be <64 hex key>:<128 hex signature>");
        }
        byte[] key = HexFormat.of().parseHex(m.group(1));
        for (Signature s : previous) {
            if (Arrays.equals(s.key(), key)) {
                throw malformed("two signatures by the same key");
            }
        }
        if (previous.size() >= MAX_SIGNATURES) {
            throw malformed("more than " + MAX_SIGNATURES + " signatures");
        }
        return new Signature(key, HexFormat.of().parseHex(m.group(2)));
    }

    private static List<String> parseRequests(String text) {
        if (text.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : text.split(",", -1)) {
            String t = part.strip();
            if (t.isEmpty() || !t.equals(part)) {
                throw malformed("requests: empty entry or stray spaces in '" + text + "'");
            }
            try {
                String canonical = Capability.parse(t).toString();
                if (out.contains(canonical)) throw malformed("requests: '" + t + "' listed twice");
                out.add(canonical);
            } catch (IllegalArgumentException e) {
                throw malformed("requests: " + e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    private static List<Dependency> parseDepends(String text) {
        if (text.isEmpty()) return List.of();
        List<Dependency> out = new ArrayList<>();
        for (String part : text.split(",", -1)) {
            int at = part.indexOf('@');
            if (at <= 0 || !ID.matcher(part.substring(0, at)).matches() || !HEX64.matcher(part.substring(at + 1)).matches()) {
                throw malformed("depends entries are <id>@<payload sha256>: '" + part + "'");
            }
            Dependency d = new Dependency(part.substring(0, at), part.substring(at + 1));
            if (out.stream().anyMatch(x -> x.id().equals(d.id()))) {
                throw malformed("depends: '" + d.id() + "' listed twice");
            }
            out.add(d);
        }
        if (out.size() > 32) {
            throw malformed("more than 32 dependencies");
        }
        return List.copyOf(out);
    }

    private static int parseInt(String s, String key, int min, int max) {
        return (int) parseLong(s, key, min, max);
    }

    private static long parseLong(String s, String key, long min, long max) {
        if (!s.matches("0|[1-9]\\d{0,17}")) {
            throw malformed(key + " must be a plain decimal number");
        }
        long v = Long.parseLong(s);
        if (v < min || v > max) {
            throw malformed(key + " must be between " + min + " and " + max);
        }
        return v;
    }

    /** Refuses anything that is not a valid package id (ids become directory names, so this also stops path traversal). */
    static void malformedUnlessId(String id) {
        if (id == null || !ID.matcher(id).matches() || id.length() > 128) {
            throw malformed("not a package id: " + id);
        }
    }

    static PackageException malformed(String message) {
        return new PackageException(Reason.MALFORMED, message);
    }

    // ── writing (publisher side) ──────────────────────────────────────────────────────────────────────────────────────

    /** Fields of a manifest to be signed. */
    public record Draft(String id, String version, int api, Sandboxing sandbox, String main, String payloadSha256,
                        long payloadSize, String publisher, List<String> requests, List<String> depends,
                        String description) {

        /** The canonical unsigned manifest; parsing it back validates every field. */
        public byte[] toBytes() {
            StringBuilder sb = new StringBuilder(MAGIC).append('\n');
            Map<String, String> f = new LinkedHashMap<>();
            f.put("id", id);
            f.put("version", version);
            f.put("noray.api", Integer.toString(api));
            f.put("sandbox", sandbox.text());
            f.put("main", main);
            f.put("payload.sha256", payloadSha256);
            f.put("payload.size", Long.toString(payloadSize));
            f.put("publisher", publisher);
            if (requests != null && !requests.isEmpty()) f.put("requests", String.join(",", requests));
            if (depends != null && !depends.isEmpty()) f.put("depends", String.join(",", depends));
            if (description != null && !description.isEmpty()) f.put("description", description);
            for (String k : ORDER) {
                if (f.containsKey(k)) sb.append(k).append('=').append(f.get(k)).append('\n');
            }
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            parse(bytes);
            return bytes;
        }
    }

    /** This manifest with one more signature appended (replacing an earlier signature by the same key). */
    public byte[] withSignature(byte[] publicKey, byte[] signature) {
        StringBuilder sb = new StringBuilder(new String(body, StandardCharsets.UTF_8));
        HexFormat hex = HexFormat.of();
        for (Signature s : signatures) {
            if (!Arrays.equals(s.key(), publicKey)) {
                sb.append("signature=").append(s.keyHex()).append(':').append(hex.formatHex(s.signature())).append('\n');
            }
        }
        sb.append("signature=").append(hex.formatHex(publicKey)).append(':').append(hex.formatHex(signature)).append('\n');
        byte[] out = sb.toString().getBytes(StandardCharsets.UTF_8);
        parse(out);
        return out;
    }

    // ── accessors ─────────────────────────────────────────────────────────────────────────────────────────────────────

    public String id() { return id; }
    public Version version() { return version; }
    public int api() { return api; }
    public Sandboxing sandbox() { return sandbox; }
    public String main() { return main; }
    public String payloadSha256() { return payloadSha256; }
    public long payloadSize() { return payloadSize; }
    public String publisher() { return publisher; }
    public List<String> requests() { return requests; }
    public List<Dependency> depends() { return depends; }
    public String description() { return description; }
    /** The signed bytes: every line before the first signature. */
    public byte[] body() { return body.clone(); }
    public List<Signature> signatures() { return signatures; }

    @Override
    public String toString() {
        return id + " " + version;
    }
}
