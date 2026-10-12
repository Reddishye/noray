package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.identity.AuthorityKeyFile;
import com.redactado.noray.identity.Certificate;
import com.redactado.noray.identity.EnrollmentRequest;
import com.redactado.noray.identity.IdentityService;
import com.redactado.noray.identity.RevocationList;
import com.redactado.noray.identity.Role;
import com.redactado.noray.identity.SigningKey;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.util.SecureFiles;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Admission of nodes: enrollment requests, certificates, authority keys and revocations. */
public final class IdentityCommand implements Command {

    @Override public Set<String> switchFlags() { return Set.of("no-passphrase"); }

    @Override public String name() { return "identity"; }
    @Override public String description() { return "Node identity, certificates and revocations"; }
    @Override public String synopsis() { return "identity show|request|install|enroll|peers|issue|sign|revoke|apply|authority ..."; }
    @Override public CommandCategory category() { return CommandCategory.SYSTEM; }

    @Override
    public String longHelp() {
        return """
            On a cluster node:
              identity show                     this node's id, keys, certificate and trust settings
              identity request                  the enrollment request to take to the authority
              identity install CERT             install the certificate issued for this node
              identity enroll CERT [host:port]  admit a peer from its certificate (keys, expiry and rights come with it)
              identity peers                    peers whose certificate was verified
              identity apply LIST               apply a signed revocation list and spread it

            On the machine that holds an authority key (keep it offline; it never needs to be on a node):
              identity authority new FILE       create an authority key (encrypted: --passphrase-file F or
                                                $NORAY_AUTHORITY_PASSPHRASE; --no-passphrase to store it plain)
              identity authority pub FILE       print the public key of a key file
              identity authority config PUB... --threshold N
                                                print the node settings for a set of authority public keys
              (every command that reads a key file takes --passphrase-file F or $NORAY_AUTHORITY_PASSPHRASE)
              identity issue REQUEST --key FILE [--role peer|reader|operator] [--days N] [--serial N]
              identity sign DOCUMENT --key FILE add this authority's endorsement to a certificate or
                                                revocation list (needed when the threshold is above 1)
              identity revoke NODEID --key FILE [--base LIST]
                                                a new cumulative revocation list that voids every certificate
                                                issued to NODEID so far

            Roles: peer (store/read/check; the default), reader (read only), operator (also delete and list:
            give it to as few nodes as possible). Certificates should be short-lived (--days, default 30).
            Configure the trust with noray.identity.authority.keys (hex public keys) and
            noray.identity.authority.threshold.
            """;
    }

    @Override
    public int execute(CommandInvocation inv) throws Exception {
        PrintStream out = inv.out();
        var args = inv.args();
        if (args.isEmpty()) {
            out.println(longHelp());
            return 1;
        }
        IdentityService svc = inv.context().getServiceRegistry().get(IdentityService.class).orElse(null);
        try {
            switch (args.get(0)) {
                case "authority" -> {
                    return authority(inv);
                }
                case "issue" -> {
                    return issue(inv);
                }
                case "sign" -> {
                    return sign(inv);
                }
                case "revoke" -> {
                    return revoke(inv, svc);
                }
                default -> { }
            }
            if (svc == null) {
                out.println("identity: this node is not managed (set noray.identity.authority.keys)");
                return 1;
            }
            switch (args.get(0)) {
                case "show" -> show(out, svc);
                case "request" -> {
                    EnrollmentRequest r = svc.enrollmentRequest();
                    out.println(r.toText());
                    out.println("fingerprint: " + r.fingerprint() + " (compare it with the node's owner before issuing)");
                }
                case "install" -> {
                    if (args.size() != 2) return usage(out, "identity install CERT");
                    svc.install(Certificate.parse(args.get(1)));
                    out.println("installed; valid until " + svc.certificate().orElseThrow().notAfter());
                }
                case "enroll" -> {
                    if (args.size() < 2 || args.size() > 3) return usage(out, "identity enroll CERT [host:port]");
                    Certificate c = Certificate.parse(args.get(1));
                    svc.enroll(c, args.size() == 3 ? args.get(2) : "");
                    out.println("enrolled " + c.nodeId().hex().substring(0, 16) + "… " + c.ops() + " until " + c.notAfter());
                }
                case "peers" -> {
                    for (Map.Entry<NodeId, Certificate> e : svc.directory().all().entrySet()) {
                        Certificate c = e.getValue();
                        out.printf("%s  %-28s until %s%n", e.getKey().hex().substring(0, 16) + "…", c.ops(), c.notAfter());
                    }
                }
                case "apply" -> {
                    if (args.size() != 2) return usage(out, "identity apply LIST");
                    boolean applied = svc.applyRevocations(RevocationList.parse(args.get(1)));
                    out.println(applied ? "applied and spread to peers" : "nothing to do: not newer than the current list");
                }
                default -> {
                    out.println("identity: unknown action '" + args.get(0) + "'");
                    return 1;
                }
            }
            return 0;
        } catch (Exception e) {
            out.println("identity: " + e.getMessage());
            return 1;
        }
    }

    private void show(PrintStream out, IdentityService svc) {
        HexFormat hex = HexFormat.of();
        out.println("node id:      " + svc.nodeId().hex());
        out.println("signing key:  " + hex.formatHex(svc.identity().publicKey()) + (svc.identity().sealed() ? " (sealed at rest)" : " (NOT encrypted at rest: no keyring)"));
        out.println("authorities:  " + svc.trust().authorityCount() + ", threshold " + svc.trust().threshold());
        out.println("revocations:  version " + svc.trust().revocations().version());
        Certificate c = svc.certificate().orElse(null);
        if (c == null) {
            out.println("certificate:  none (run 'identity request' and take it to the authority)");
        } else {
            boolean valid = c.validAt(Instant.now(), svc.trust().skew());
            out.println("certificate:  " + c.ops() + " until " + c.notAfter() + (valid ? "" : " (EXPIRED OR NOT YET VALID)")
                    + (svc.trust().isRevoked(c) ? " (REVOKED)" : ""));
        }
    }

    // ── authority side ─────────────────────────────────────────────────────────

    private int authority(CommandInvocation inv) throws Exception {
        var a = inv.args();
        PrintStream out = inv.out();
        if (a.size() >= 2 && "config".equals(a.get(1))) {
            return authorityConfig(inv);
        }
        if (a.size() != 3) {
            return usage(out, "identity authority new|pub FILE | identity authority config PUBKEY... --threshold N");
        }
        Path file = Path.of(a.get(2));
        switch (a.get(1)) {
            case "new" -> {
                if (Files.exists(file)) {
                    out.println("identity: " + file + " already exists; refusing to overwrite a key");
                    return 1;
                }
                SigningKey k = SigningKey.generate();
                char[] pass = passphrase(inv);
                boolean plain = inv.booleanFlags().contains("no-passphrase");
                if (pass == null && !plain) {
                    out.println("identity: an authority key should be protected: give --passphrase-file FILE (or set "
                            + "NORAY_AUTHORITY_PASSPHRASE), or --no-passphrase if the file is kept safe by other means");
                    return 1;
                }
                AuthorityKeyFile.write(file, k, plain ? null : pass);
                out.println("public key: " + k.publicKeyHex());
                out.println("private key written to " + file + " (mode 0600"
                        + (plain ? ", NOT encrypted" : ", encrypted with your passphrase")
                        + "). Keep it OFF the cluster nodes and back it up.");
                return 0;
            }
            case "pub" -> {
                out.println(readKey(file, passphrase(inv)).publicKeyHex());
                return 0;
            }
            default -> {
                return usage(out, "identity authority new|pub FILE");
            }
        }
    }

    /** The settings that make a set of authority public keys the trust of a mesh. */
    private int authorityConfig(CommandInvocation inv) {
        PrintStream out = inv.out();
        var keys = inv.args().subList(2, inv.args().size());
        if (keys.isEmpty()) {
            return usage(out, "identity authority config PUBKEY... --threshold N");
        }
        int threshold = Integer.parseInt(inv.getFlag("threshold", "1"));
        if (threshold < 1 || threshold > keys.size()) {
            out.println("identity: --threshold must be between 1 and the number of keys (" + keys.size() + ")");
            return 1;
        }
        for (String k : keys) {
            if (!k.matches("[0-9a-fA-F]{64}")) {
                out.println("identity: not a public key (64 hex characters): " + k);
                return 1;
            }
        }
        out.println("noray.identity.authority.keys=" + String.join(",", keys));
        out.println("noray.identity.authority.threshold=" + threshold);
        if (threshold == 1 && keys.size() > 1) {
            out.println("# any ONE of these keys can admit or revoke a node; a threshold of 2 or more needs several to agree");
        } else if (keys.size() < threshold + 1) {
            out.println("# losing a single key blocks every future admission and revocation: keep a spare");
        }
        return 0;
    }

    private static char[] passphrase(CommandInvocation inv) throws Exception {
        String file = inv.getFlag("passphrase-file", "");
        if (!file.isEmpty()) {
            String t = Files.readString(Path.of(file));
            return t.stripTrailing().toCharArray();
        }
        String env = System.getenv("NORAY_AUTHORITY_PASSPHRASE");
        return env == null || env.isEmpty() ? null : env.toCharArray();
    }

    private int issue(CommandInvocation inv) throws Exception {
        PrintStream out = inv.out();
        if (inv.args().size() != 2) {
            return usage(out, "identity issue REQUEST --key FILE [--role peer|reader|operator] [--days N]");
        }
        SigningKey key = readKey(Path.of(required(inv, "key")), passphrase(inv));
        EnrollmentRequest req = EnrollmentRequest.parse(inv.args().get(1));
        Role role = Role.parse(inv.getFlag("role", "peer"));
        long days = Long.parseLong(inv.getFlag("days", "30"));
        if (days < 1 || days > 3650) {
            out.println("identity: --days must be between 1 and 3650");
            return 1;
        }
        long serial = Long.parseLong(inv.getFlag("serial", String.valueOf(System.currentTimeMillis())));
        Instant now = Instant.now();
        Certificate c = req.toCertificate(role.ops(), now.minusSeconds(60), now.plus(Duration.ofDays(days)), serial).endorse(key);
        out.println("# node " + req.nodeId().hex().substring(0, 16) + "…  fingerprint " + req.fingerprint()
                + "  " + role + " " + c.ops() + " for " + days + " days");
        out.println(c.toText());
        return 0;
    }

    private int sign(CommandInvocation inv) throws Exception {
        PrintStream out = inv.out();
        if (inv.args().size() != 2) {
            return usage(out, "identity sign CERT|LIST --key FILE");
        }
        SigningKey key = readKey(Path.of(required(inv, "key")), passphrase(inv));
        String doc = inv.args().get(1).trim();
        if (doc.startsWith("noray-cert:")) {
            out.println(Certificate.parse(doc).endorse(key).toText());
        } else if (doc.startsWith("noray-revoke:")) {
            out.println(RevocationList.parse(doc).endorse(key).toText());
        } else {
            out.println("identity: not a certificate or a revocation list");
            return 1;
        }
        return 0;
    }

    private int revoke(CommandInvocation inv, IdentityService svc) throws Exception {
        PrintStream out = inv.out();
        if (inv.args().size() != 2) {
            return usage(out, "identity revoke NODEID --key FILE [--base LIST]");
        }
        SigningKey key = readKey(Path.of(required(inv, "key")), passphrase(inv));
        NodeId node = new NodeId(inv.args().get(1));
        RevocationList base;
        String baseText = inv.getFlag("base", "");
        if (!baseText.isEmpty()) {
            base = RevocationList.parse(baseText);
        } else if (svc != null) {
            base = svc.trust().revocations();
        } else {
            out.println("identity: run this on a managed node or pass the current list with --base "
                    + "(lists are cumulative: a new one must keep every earlier entry)");
            return 1;
        }
        Map<NodeId, Long> entries = new LinkedHashMap<>(base.entries());
        entries.put(node, System.currentTimeMillis());
        RevocationList next = RevocationList.unsigned(base.version() + 1, System.currentTimeMillis(), entries).endorse(key);
        out.println("# revokes every certificate issued to " + node.hex().substring(0, 16) + "… before now");
        out.println(next.toText());
        return 0;
    }

    private static SigningKey readKey(Path file, char[] passphrase) throws Exception {
        return AuthorityKeyFile.read(file, passphrase);
    }

    private static String required(CommandInvocation inv, String flag) {
        String v = inv.getFlag(flag, "");
        if (v.isEmpty()) {
            throw new IllegalArgumentException("--" + flag + " is required");
        }
        return v;
    }

    private static int usage(PrintStream out, String text) {
        out.println("Usage: " + text);
        return 1;
    }
}
