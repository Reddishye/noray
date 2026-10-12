package com.redactado.noray.filesystem;

import com.redactado.noray.identity.Certificate;
import com.redactado.noray.identity.IdentityService;
import com.redactado.noray.identity.Role;
import com.redactado.noray.identity.SigningKey;
import com.redactado.noray.kernel.core.NorayKernel;
import com.redactado.noray.net.NetModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The kernel wiring of identity: configuration in, a managed node with a sealed key and a persistent certificate out. */
class ManagedBootTest {

    @TempDir
    Path tmp;

    private NorayKernel kernel(String authorityKeys, String passphrase) {
        Map<String, String> props = new HashMap<>();
        props.put("noray.data.dir", tmp.resolve("data").toString());
        props.put("noray.net.udp.port", "0");
        props.put("noray.net.mesh.psk.insecure", "true");
        props.put("noray.keyring.argon2.insecure", "true");
        props.put("noray.keyring.argon2.memory.kib", "64");
        props.put("noray.keyring.argon2.iterations", "1");
        props.put("noray.fs.namespace.sync", "false");
        props.put("noray.identity.maintenance.interval.s", "0");
        if (authorityKeys != null) {
            props.put("noray.identity.authority.keys", authorityKeys);
        }
        if (passphrase != null) {
            props.put("noray.keyring.passphrase", passphrase);
        }
        NorayKernel k = new NorayKernel(props);
        k.registerModule(new NetModule());
        k.registerModule(new FsModule());
        return k;
    }

    @Test
    void withoutAnAuthorityTheNodeRunsUnmanaged() throws Exception {
        try (NorayKernel k = kernel(null, "pw")) {
            k.boot();
            assertTrue(k.getContext().getServiceRegistry().get(IdentityService.class).isEmpty());
        }
    }

    @Test
    void aManagedNodeSealsItsKeyAndKeepsItsCertificateAcrossRestarts() throws Exception {
        SigningKey authority = SigningKey.generate();
        Certificate issued;
        byte[] pub;
        try (NorayKernel k = kernel(authority.publicKeyHex(), "pw")) {
            k.boot();
            IdentityService svc = k.getContext().getServiceRegistry().require(IdentityService.class);
            assertTrue(svc.identity().sealed(), "the keyring seals the node key");
            assertTrue(svc.certificate().isEmpty());
            var witness = k.getContext().getServiceRegistry().require(com.redactado.noray.identity.witness.WitnessService.class);
            witness.runOnce();
            assertTrue(Files.exists(tmp.resolve("data").resolve("identity").resolve("witness").resolve("own.log")));
            assertTrue(witness.alarms().isEmpty());
            assertEquals(32, svc.enrollmentRequest().transportKey().length, "transport key comes from the net module");
            assertTrue(svc.enrollmentRequest().kemKey().length > 1000, "and so does the post-quantum key");

            issued = svc.enrollmentRequest().toCertificate(Role.PEER.ops(), Instant.now().minusSeconds(60),
                    Instant.now().plus(Duration.ofDays(30)), 1).endorse(authority);
            svc.install(issued);
            pub = svc.identity().publicKey();
        }
        assertTrue(Files.exists(tmp.resolve("data").resolve("identity").resolve("node.cert")));
        try (NorayKernel k = kernel(authority.publicKeyHex(), "pw")) {
            k.boot();
            IdentityService svc = k.getContext().getServiceRegistry().require(IdentityService.class);
            assertArrayEquals(pub, svc.identity().publicKey(), "same identity after a restart");
            assertEquals(issued.serial(), svc.certificate().orElseThrow().serial());
            var witness = k.getContext().getServiceRegistry().require(com.redactado.noray.identity.witness.WitnessService.class);
            assertTrue(witness.alarms().isEmpty(), "a clean restart is not a rollback: " + witness.alarms());
            assertTrue(witness.ownCheckpoints() >= 1);
        }
    }

    @Test
    void aMalformedAuthorityKeyStopsTheBoot() {
        try (NorayKernel k = kernel("not-hex", "pw")) {
            assertThrows(Exception.class, k::boot);
        }
    }
}
