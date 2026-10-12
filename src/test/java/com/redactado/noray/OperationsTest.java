package com.redactado.noray;

import com.redactado.noray.kernel.metrics.Metrics;
import com.redactado.noray.kernel.metrics.MetricsHttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Configuration sources and the metrics endpoint: what an operator touches. */
class OperationsTest {

    @TempDir Path tmp;

    @Test
    void settingsComeFromDefaultsFileSystemPropertiesAndArgumentsInThatOrder() throws Exception {
        Path file = tmp.resolve("n.properties");
        Files.writeString(file, "noray.net.udp.port=7000\nnoray.env=staging\nnoray.witness.max=3\n");
        Properties sys = new Properties();
        sys.setProperty("noray.witness.max", "4");
        sys.setProperty("unrelated", "x");

        Map<String, String> s = NorayConfig.load(new String[]{"--config", file.toString(), "--set", "noray.net.udp.port=7001"},
                Map.of(), sys);
        assertEquals("7001", s.get("noray.net.udp.port"), "--set wins");
        assertEquals("4", s.get("noray.witness.max"), "system property beats the file");
        assertEquals("staging", s.get("noray.env"), "file beats the default");
        assertEquals("100", s.get("noray.max.threads"), "default");
        assertFalse(s.containsKey("unrelated"));
    }

    @Test
    void aMissingConfigurationFileIsAnErrorNotASilentDefault() {
        assertThrows(IllegalStateException.class,
                () -> NorayConfig.load(new String[]{"--config", tmp.resolve("nope").toString()}, Map.of(), new Properties()));
        assertThrows(IllegalStateException.class,
                () -> NorayConfig.load(new String[0], Map.of("NORAY_CONFIG", tmp.resolve("nope").toString()), new Properties()));
        assertThrows(IllegalArgumentException.class,
                () -> NorayConfig.load(new String[]{"--set", "novalue"}, Map.of(), new Properties()));
    }

    @Test
    void metricsAreCountedExposedAndServedOnLoopback() throws Exception {
        Metrics m = new Metrics();
        m.inc("net.packets.out");
        m.add("net.bytes.out", 1500);
        m.gauge("volumes.online", () -> 3);
        m.gauge("broken", () -> { throw new IllegalStateException(); });
        assertEquals(1500, m.count("net.bytes.out"));
        assertEquals(3, m.snapshot().get("volumes.online"));
        assertFalse(m.snapshot().containsKey("broken"));
        assertTrue(m.prometheus().contains("noray_net_bytes_out 1500"));
        assertTrue(m.json().contains("\"volumes.online\":3"));

        try (MetricsHttpServer server = new MetricsHttpServer(m, 0)) {
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("noray_net_packets_out 1"));
            HttpResponse<String> post = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/metrics"))
                    .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, post.statusCode());
        }
    }
}
