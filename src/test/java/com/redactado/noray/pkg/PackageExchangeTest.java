package com.redactado.noray.pkg;

import com.redactado.noray.identity.SigningKey;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import com.redactado.noray.net.InMemoryMeshFabric;
import com.redactado.noray.net.InMemoryMeshTransport;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.PeerCapabilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** M5: one's own nodes hand packages to each other by payload hash; trust stays local to each node. */
class PackageExchangeTest {

    @TempDir
    Path tmp;

    private static NodeId id(int n) {
        return new NodeId(String.format("%064x", n));
    }

    @Test
    void aPeerHandsOverBytesButTheReceiverDecides() throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            InMemoryMeshFabric fabric = new InMemoryMeshFabric();
            PeerCapabilities caps = new PeerCapabilities(true, false, Set.of());
            InMemoryMeshTransport ta = new InMemoryMeshTransport(id(1), new ConcurrentEventBus(ex, (t, c) -> { }), fabric, true);
            InMemoryMeshTransport tb = new InMemoryMeshTransport(id(2), new ConcurrentEventBus(ex, (t, c) -> { }), fabric, true);
            ta.enroll(id(2), caps);
            tb.enroll(id(1), caps);

            SigningKey key = SigningKey.generate();
            byte[] payload = "a tool".getBytes(StandardCharsets.UTF_8);
            PackageManager a = new PackageManager(tmp.resolve("a"));
            a.pinPublisher(TestPackages.publisher("example", false, 1, key));
            PackageManifest m = a.install(TestPackages.archive(TestPackages.draft("com.example.tool", "1.0.0",
                    PackageManifest.Sandboxing.PROCESS, payload, "example", List.of()), payload, key), false);

            PackageManager b = new PackageManager(tmp.resolve("b"));
            try (PackageExchange serveA = new PackageExchange(ta, a); PackageExchange askB = new PackageExchange(tb, b)) {
                var got = askB.fetch(id(1), m.payloadSha256(), Duration.ofSeconds(5));
                assertTrue(got.isPresent());
                // B has not pinned the publisher: the bytes arrived, but they are not installed
                assertEquals(PackageException.Reason.UNPINNED_PUBLISHER,
                        assertThrows(PackageException.class, () -> b.install(got.get(), false)).reason());
                b.pinPublisher(TestPackages.publisher("example", false, 1, key));
                assertEquals("com.example.tool 1.0.0", b.install(got.get(), false).toString());
                assertFalse(b.list().getFirst().approved(), "fetching never approves");

                assertTrue(askB.fetch(id(1), "0".repeat(64), Duration.ofSeconds(5)).isEmpty());
                assertTrue(a.audit().tail(5).stream().anyMatch(l -> l.contains("\"event\":\"served\"")));
            }
        } finally {
            ex.shutdownNow();
        }
    }
}
