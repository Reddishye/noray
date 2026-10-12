package com.redactado.noray.command;

import com.redactado.noray.command.builtin.IdentityCommand;
import com.redactado.noray.identity.*;
import com.redactado.noray.kernel.api.*;
import com.redactado.noray.kernel.core.DefaultServiceRegistry;
import com.redactado.noray.net.NodeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

/** The operator workflow end to end: authority key, request, issue, install, revoke, apply. */
class IdentityCommandTest {

    @TempDir Path tmp;

    private String run(DefaultServiceRegistry services, int[] code, Map<String, String> flags, String... args) throws Exception {
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
        PrintStream ps = new PrintStream(buf);
        CommandInvocation inv = CommandInvocation.builder().rawCommandLine("identity").commandName("identity")
                .args(List.of(args)).flags(flags).context(ctx).out(ps).err(ps).build();
        code[0] = new IdentityCommand().execute(inv);
        ps.flush();
        return buf.toString();
    }

    private String passFile() {
        try {
            Path f = tmp.resolve("pass.txt");
            if (!java.nio.file.Files.exists(f)) {
                java.nio.file.Files.writeString(f, "correct horse battery staple\n");
            }
            return f.toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> pass() {
        return Map.of("passphrase-file", passFile());
    }

    @Test
    void authorityKeysAreEncryptedAndConfigIsPrinted() throws Exception {
        int[] code = new int[1];
        DefaultServiceRegistry offline = new DefaultServiceRegistry();
        Path key = tmp.resolve("enc.key");
        run(offline, code, pass(), "authority", "new", key.toString());
        assertEquals(0, code[0]);
        assertTrue(com.redactado.noray.identity.AuthorityKeyFile.isProtected(key));
        assertFalse(java.nio.file.Files.readString(key).matches("[0-9a-f]{64}\\s*"));
        run(offline, code, Map.of(), "authority", "pub", key.toString());
        assertEquals(1, code[0], "a protected key without its passphrase is refused");
        java.nio.file.Files.writeString(tmp.resolve("wrong.txt"), "nope");
        String wrong = run(offline, code, Map.of("passphrase-file", tmp.resolve("wrong.txt").toString()),
                "authority", "pub", key.toString());
        assertEquals(1, code[0], wrong);
        run(offline, code, Map.of(), "authority", "new", tmp.resolve("x.key").toString());
        assertEquals(1, code[0], "without a passphrase and without --no-passphrase no key is created");

        String a = "ab".repeat(32), b = "cd".repeat(32), c = "ef".repeat(32);
        String cfg = run(offline, code, Map.of("threshold", "2"), "authority", "config", a, b, c);
        assertEquals(0, code[0]);
        assertTrue(cfg.contains("noray.identity.authority.keys=" + a + "," + b + "," + c));
        assertTrue(cfg.contains("noray.identity.authority.threshold=2"));
        run(offline, code, Map.of("threshold", "4"), "authority", "config", a, b, c);
        assertEquals(1, code[0]);
    }

    private static String lastLine(String out) {
        String[] lines = out.strip().split("\n");
        return lines[lines.length - 1].trim();
    }

    @Test
    void operatorWorkflow() throws Exception {
        int[] code = new int[1];
        DefaultServiceRegistry offline = new DefaultServiceRegistry(); // the authority's machine: no node needed
        Path keyFile = tmp.resolve("authority.key");

        String created = run(offline, code, pass(), "authority", "new", keyFile.toString());
        assertEquals(0, code[0], created);
        String pub = run(offline, code, pass(), "authority", "pub", keyFile.toString()).trim();
        assertTrue(created.contains(pub));
        run(offline, code, pass(), "authority", "new", keyFile.toString());
        assertEquals(1, code[0], "an existing key is never overwritten");

        // a managed node trusting that authority
        TestClock clock = new TestClock();
        TrustRoot trust = new TrustRoot(List.of(HexFormat.of().parseHex(pub)), 1);
        NodeIdentity identity = NodeIdentity.loadOrCreate(tmp.resolve("node"), null);
        NodeId me = new NodeId("ab".repeat(32));
        IdentityService svc = new IdentityService(me, new byte[32], identity, trust, new PeerDirectory(trust, clock),
                tmp.resolve("node").resolve("node.cert"), java.time.Clock.systemUTC());
        DefaultServiceRegistry node = new DefaultServiceRegistry();
        node.register(IdentityService.class, svc);

        String show = run(node, code, Map.of(), "show");
        assertTrue(show.contains("certificate:  none"), show);

        String request = lastLine(run(node, code, Map.of(), "request").lines().filter(l -> l.startsWith("noray-id:")).findFirst().orElseThrow());
        String issued = run(offline, code, Map.of("key", keyFile.toString(), "role", "operator", "days", "10", "passphrase-file", passFile()), "issue", request);
        assertEquals(0, code[0], issued);
        String cert = lastLine(issued);
        assertTrue(cert.startsWith("noray-cert:1:"));

        String installed = run(node, code, Map.of(), "install", cert);
        assertEquals(0, code[0], installed);
        assertTrue(run(node, code, Map.of(), "show").contains("DELETE"));

        // an unendorsed or foreign document is refused
        String forged = Certificate.parse(cert).encode().length > 0
                ? EnrollmentRequest.parse(request).toCertificate(Role.OPERATOR.ops(), java.time.Instant.now().minusSeconds(60),
                java.time.Instant.now().plusSeconds(3600), 1).toText() : "";
        run(node, code, Map.of(), "install", forged);
        assertEquals(1, code[0]);

        // revoke another node: a new cumulative list, then apply
        String victim = "cd".repeat(32);
        String revoke = run(node, code, Map.of("key", keyFile.toString(), "passphrase-file", passFile()), "revoke", victim);
        assertEquals(0, code[0], revoke);
        String list = lastLine(revoke);
        assertTrue(run(node, code, Map.of(), "apply", list).contains("applied"));
        assertEquals(1, trust.revocations().version());
        assertTrue(run(node, code, Map.of(), "apply", list).contains("nothing to do"));

        String second = lastLine(run(node, code, Map.of("key", keyFile.toString(), "passphrase-file", passFile()), "revoke", "ef".repeat(32)));
        run(node, code, Map.of(), "apply", second);
        assertEquals(2, trust.revocations().version());
        assertEquals(2, trust.revocations().entries().size(), "revocations accumulate");
    }

    @Test
    void thresholdSigningAcrossTwoAuthorities() throws Exception {
        int[] code = new int[1];
        DefaultServiceRegistry offline = new DefaultServiceRegistry();
        Path k1 = tmp.resolve("a1"), k2 = tmp.resolve("a2");
        run(offline, code, pass(), "authority", "new", k1.toString());
        run(offline, code, pass(), "authority", "new", k2.toString());
        String p1 = run(offline, code, pass(), "authority", "pub", k1.toString()).trim();
        String p2 = run(offline, code, pass(), "authority", "pub", k2.toString()).trim();
        TrustRoot trust = new TrustRoot(List.of(HexFormat.of().parseHex(p1), HexFormat.of().parseHex(p2)), 2);

        NodeIdentity identity = NodeIdentity.loadOrCreate(tmp.resolve("n"), null);
        EnrollmentRequest req = new EnrollmentRequest(new NodeId("12".repeat(32)), identity.publicKey(), new byte[0]);
        String first = lastLine(run(offline, code, Map.of("key", k1.toString(), "passphrase-file", passFile()), "issue", req.toText()));
        assertThrows(Exception.class, () -> trust.verify(Certificate.parse(first), java.time.Instant.now()));
        String both = lastLine(run(offline, code, Map.of("key", k2.toString(), "passphrase-file", passFile()), "sign", first));
        assertEquals(0, code[0]);
        trust.verify(Certificate.parse(both), java.time.Instant.now());
    }
}
