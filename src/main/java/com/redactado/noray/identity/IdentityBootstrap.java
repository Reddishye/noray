package com.redactado.noray.identity;

import com.redactado.noray.data.crypto.Keyring;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.kernel.api.Configuration;
import com.redactado.noray.net.MeshTransport;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Builds the identity machinery from configuration.
 *
 * <ul>
 *   <li>{@code noray.identity.authority.keys}: comma-separated hex public keys of the authority (required to
 *       enable certificates; without it the node runs unmanaged and every enrolled peer may do everything)</li>
 *   <li>{@code noray.identity.authority.threshold}: how many of them must endorse a document (default 1)</li>
 *   <li>{@code noray.identity.maintenance.interval.s}: housekeeping period (default 60, 0 = off)</li>
 *   <li>{@code noray.identity.skew.s}: tolerated clock difference when checking validity (default 300)</li>
 * </ul>
 */
public final class IdentityBootstrap {

    /** The wiring result. {@code policy} is what the blob responder must use. */
    public record Managed(IdentityService service, AccessPolicy policy, CertificateExchange exchange,
                          IdentityMaintenance maintenance) {
    }

    private IdentityBootstrap() {
    }

    /** @return {@code null} when no authority is configured (unmanaged mode) */
    public static Managed create(Configuration config, Path dataDir, Keyring keyring, MeshTransport transport,
                                 byte[] transportKey, Clock clock) {
        return create(config, dataDir, keyring, transport, transportKey, new byte[0], PeerEnroller.NONE, clock);
    }

    /**
     * @param kemKey   this node's ML-KEM public key (empty when the transport does not use one)
     * @param enroller how admitting and removing a peer reaches the transport
     */
    public static Managed create(Configuration config, Path dataDir, Keyring keyring, MeshTransport transport,
                                 byte[] transportKey, byte[] kemKey, PeerEnroller enroller, Clock clock) {
        String keysText = config.get("noray.identity.authority.keys", "");
        if (keysText == null || keysText.isBlank()) {
            return null;
        }
        List<byte[]> keys = new ArrayList<>();
        for (String k : keysText.split("[,\\s]+")) {
            if (!k.isBlank()) {
                try {
                    keys.add(HexFormat.of().parseHex(k.trim()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("noray.identity.authority.keys: '" + k + "' is not a hex key", e);
                }
            }
        }
        int threshold = config.getInt("noray.identity.authority.threshold", 1);
        Duration skew = Duration.ofSeconds(config.getInt("noray.identity.skew.s", (int) TrustRoot.DEFAULT_SKEW.toSeconds()));
        Path dir = dataDir.resolve("identity");
        TrustRoot trust = new TrustRoot(keys, threshold, skew, dir.resolve("revocations"));
        PeerDirectory directory = new PeerDirectory(trust, clock);
        NodeIdentity identity = NodeIdentity.loadOrCreate(dataDir, keyring);
        IdentityService service = new IdentityService(transport.localId(), transportKey, kemKey, identity, trust, directory,
                dir.resolve("node.cert"), clock, enroller);
        CertificateExchange exchange = new CertificateExchange(transport, directory, trust,
                () -> service.certificate().orElse(null), service::acceptRevocations);
        service.attach(exchange);
        exchange.start();

        // A peer that asks before we know its certificate gets refused, and we introduce ourselves so that it
        // answers with its own: the retry succeeds without any manual step.
        AccessPolicy policy = (peer, op) -> {
            try {
                directory.check(peer, op);
            } catch (FsException e) {
                if (!directory.knows(peer)) {
                    exchange.announce(peer);
                }
                throw e;
            }
        };
        exchange.announceAll();
        IdentityMaintenance maintenance = new IdentityMaintenance(service, exchange, clock,
                Duration.ofSeconds(Math.max(1, config.getInt("noray.identity.maintenance.interval.s", 60))));
        if (config.getInt("noray.identity.maintenance.interval.s", 60) > 0) {
            maintenance.start();
        }
        return new Managed(service, policy, exchange, maintenance);
    }
}
