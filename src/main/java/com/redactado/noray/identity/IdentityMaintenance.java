package com.redactado.noray.identity;

import com.redactado.noray.net.NodeId;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the identity state tidy while the node runs: forgets expired and revoked certificates, re-introduces this
 * node now and then (which also spreads the revocation list to peers that were away), and warns while this node's
 * own certificate is about to expire, because an expired certificate silently cuts the node off from its peers.
 */
public final class IdentityMaintenance implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(IdentityMaintenance.class.getName());
    private static final Duration WARN_BEFORE_EXPIRY = Duration.ofDays(7);
    private static final int ANNOUNCE_EVERY = 10;

    private final IdentityService service;
    private final CertificateExchange exchange;
    private final Clock clock;
    private final Duration interval;
    private ScheduledExecutorService scheduler;
    private int cycle;
    private long lastWarnedMillis;

    public IdentityMaintenance(IdentityService service, CertificateExchange exchange, Clock clock, Duration interval) {
        this.service = service;
        this.exchange = exchange;
        this.clock = clock;
        this.interval = interval;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "noray-identity-maintenance");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "identity maintenance failed: " + e.getMessage());
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** One pass; public so that it can be driven directly. Returns the peers whose certificates were dropped. */
    public synchronized List<NodeId> runOnce() {
        List<NodeId> dropped = service.purge();
        if (++cycle % ANNOUNCE_EVERY == 0) {
            exchange.announceAll();
        }
        service.certificate().ifPresent(c -> {
            long now = clock.millis();
            long left = c.notAfter().toEpochMilli() - now;
            if (left < WARN_BEFORE_EXPIRY.toMillis() && now - lastWarnedMillis > Duration.ofHours(6).toMillis()) {
                lastWarnedMillis = now;
                LOG.log(System.Logger.Level.WARNING, left <= 0
                        ? "identity: this node's certificate has EXPIRED; peers refuse it until a new one is installed"
                        : "identity: this node's certificate expires in " + Duration.ofMillis(left).toHours() + " hours");
            }
        });
        return dropped;
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
