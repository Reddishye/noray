package com.redactado.noray.kernel.metrics;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Serves {@code /metrics} (Prometheus text) and {@code /metrics.json} on the loopback interface only; an operator who
 * wants it elsewhere puts a reverse proxy with authentication in front. Read-only, no request body is looked at.
 */
public final class MetricsHttpServer implements AutoCloseable {

    private final HttpServer server;

    public MetricsHttpServer(Metrics metrics, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 8);
        server.createContext("/metrics", ex -> send(ex, "text/plain; version=0.0.4", metrics.prometheus()));
        server.createContext("/metrics.json", ex -> send(ex, "application/json", metrics.json()));
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private static void send(com.sun.net.httpserver.HttpExchange ex, String type, String body) throws IOException {
        try (ex) {
            if (!"GET".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", type);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
