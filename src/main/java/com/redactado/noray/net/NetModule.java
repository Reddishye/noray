package com.redactado.noray.net;

import com.redactado.noray.kernel.api.InitLevel;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.kernel.api.KernelModule;
import com.redactado.noray.net.api.NetFramework;
import com.redactado.noray.net.enroll.EnrollmentStore;
import com.redactado.noray.net.enroll.FileEnrollmentStore;
import com.redactado.noray.net.identity.LocalIdentity;
import com.redactado.noray.net.identity.LocalIdentityStore;
import com.redactado.noray.net.internal.DefaultNetFramework;
import com.redactado.noray.net.internal.DefaultPathPolicy;
import com.redactado.noray.net.internal.EndpointDriverRegistry;
import com.redactado.noray.net.internal.LoopbackEndpointDriver;
import com.redactado.noray.net.internal.PersistingMeshTransport;
import com.redactado.noray.net.internal.RoutingMeshTransport;
import com.redactado.noray.net.overlay.NoiseUdpEndpointDriver;
import com.redactado.noray.net.overlay.StaticKeys;
import com.redactado.noray.net.overlay.UdpOverlay;
import com.redactado.noray.net.spi.PathPolicy;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Set;

public class NetModule implements KernelModule {

    @Override
    public String id() {
        return "noray.net";
    }

    @Override
    public Set<String> dependencies() {
        return Collections.emptySet();
    }

    @Override
    public InitLevel getInitLevel() {
        return InitLevel.CORE;
    }

    @Override
    public int getOrderWithinLevel() {
        return 0;
    }

    @Override
    public boolean isCritical() {
        return true;
    }

    @Override
    public void init(KernelContext context) throws Exception {
        String enrollment = context.getConfiguration().get("noray.net.enrollment.required", "");
        if (enrollment.isEmpty()) {
            context.getConfiguration().set("noray.net.enrollment.required", "true");
        }
        boolean enrollmentRequired = context.getConfiguration().getBoolean("noray.net.enrollment.required", true);

        String data = context.getConfiguration().get("noray.data.dir", "data");
        Path dataDir = Path.of(data);

        LocalIdentity identity = new LocalIdentityStore(dataDir).loadOrCreate();
        NodeId localId = identity.nodeId();
        com.redactado.noray.data.crypto.Keyring keyring = context.getServiceRegistry()
                .get(com.redactado.noray.data.crypto.Keyring.class).orElse(null);
        if (keyring == null) {
            keyring = com.redactado.noray.data.crypto.KeyringProvider.open(context, dataDir);
            if (keyring != null) {
                context.registerResource(id(), keyring);
                context.getServiceRegistry().register(com.redactado.noray.data.crypto.Keyring.class, keyring);
            }
        }
        StaticKeys keys = StaticKeys.loadOrCreate(dataDir, localId, keyring);
        context.getServiceRegistry().register(StaticKeys.class, keys);

        InMemoryMeshFabric fabric = new InMemoryMeshFabric();
        InMemoryMeshTransport memory = new InMemoryMeshTransport(
                localId, context.getEventBus(), fabric, enrollmentRequired);

        EnrollmentStore store = new FileEnrollmentStore(dataDir);
        context.getServiceRegistry().register(EnrollmentStore.class, store);
        PersistingMeshTransport persisting = new PersistingMeshTransport(memory, store);
        persisting.loadPersisted();

        String allow = context.getConfiguration().get("noray.net.path.allow", "");
        if (allow.isEmpty()) {
            context.getConfiguration().set("noray.net.path.allow", "loopback,intro,noise-udp");
        }

        // The mesh key is part of what keeps outsiders from talking to a node and, with obfuscation, from recognising it.
        // Silently running without one would be the default nobody notices, so it has to be a decision.
        String pskHex = context.getConfiguration().get("noray.net.mesh.psk", "");
        byte[] psk = new byte[32];
        if (pskHex.length() == 64) {
            psk = HexFormat.of().parseHex(pskHex);
        } else if (!pskHex.isEmpty()) {
            throw new IllegalStateException("noray.net.mesh.psk must be 64 hex characters (32 bytes)");
        } else if (context.getConfiguration().getBoolean("noray.net.mesh.psk.insecure", false)) {
            System.getLogger(NetModule.class.getName()).log(System.Logger.Level.WARNING,
                    "net: running with an all-zero mesh key (noray.net.mesh.psk.insecure=true); peers are still "
                            + "authenticated by their enrolled static keys, but nothing else keeps outsiders out");
        } else {
            throw new IllegalStateException("noray.net.mesh.psk is not set. Generate one with `openssl rand -hex 32`, "
                    + "give the same value to every node of the mesh, or set noray.net.mesh.psk.insecure=true "
                    + "for a throwaway test node");
        }

        int udpPort = context.getConfiguration().getInt("noray.net.udp.port", 0);
        UdpOverlay overlay = new UdpOverlay(localId, keys, store, psk, udpPort);
        String previousHex = context.getConfiguration().get("noray.net.mesh.psk.previous", "");
        if (previousHex != null && !previousHex.isEmpty()) {
            if (previousHex.length() != 64) {
                throw new IllegalStateException("noray.net.mesh.psk.previous must be 64 hex characters (32 bytes)");
            }
            overlay.rotateFrom(HexFormat.of().parseHex(previousHex));
        }
        overlay.obfuscate(context.getConfiguration().getBoolean("noray.net.obfuscate", false));
        overlay.enableRelay(context.getConfiguration().getBoolean("noray.net.relay.enabled", false),
                context.getConfiguration().getInt("noray.net.relay.kib.s", 2048) * 1024L);
        String relays = context.getConfiguration().get("noray.net.relay.peers", "");
        if (relays != null && !relays.isBlank()) {
            java.util.Set<NodeId> set = new java.util.LinkedHashSet<>();
            for (String r : relays.split("[,\\s]+")) {
                if (!r.isBlank()) set.add(new NodeId(r.trim()));
            }
            overlay.keepConnected(set, context.getConfiguration().getInt("noray.net.relay.keepalive.s", 15));
        }
        overlay.limits(context.getConfiguration().getInt("noray.net.max.message.mib", 16) * 1024 * 1024,
                context.getConfiguration().getInt("noray.net.send.timeout.s", 30) * 1000L);
        overlay.peerBandwidth(context.getConfiguration().getInt("noray.net.peer.kib.s", 0) * 1024L);
        overlay.shapeTraffic(context.getConfiguration().getInt("noray.net.pad.bucket", 256),
                context.getConfiguration().getInt("noray.net.cover.interval.ms", 0));

        EndpointDriverRegistry endpointDriverRegistry = new EndpointDriverRegistry();
        PathPolicy pathPolicy = new DefaultPathPolicy();
        endpointDriverRegistry.register(new LoopbackEndpointDriver());
        endpointDriverRegistry.register(new NoiseUdpEndpointDriver(overlay));

        RoutingMeshTransport routing = new RoutingMeshTransport(
                persisting, store, endpointDriverRegistry, overlay);

        context.getServiceRegistry().register(MeshTransport.class, routing);
        NetFramework netFw = new DefaultNetFramework(routing, endpointDriverRegistry, pathPolicy);
        context.getServiceRegistry().register(NetFramework.class, netFw);
        context.getServiceRegistry().register(PathPolicy.class, pathPolicy);
        context.registerResource(id(), routing);
        startOnion(context, routing);
    }

    /** Anonymous message delivery over relays; off unless {@code noray.net.onion.enabled=true}. */
    private void startOnion(KernelContext context, MeshTransport mesh) {
        var cfg = context.getConfiguration();
        if (!cfg.getBoolean("noray.net.onion.enabled", false)) {
            return;
        }
        int hops = cfg.getInt("noray.net.onion.hops", 2);
        int minHops = cfg.getInt("noray.net.onion.min.hops", Math.min(1, hops));
        var onionCfg = com.redactado.noray.net.onion.OnionConfig.defaults().withHops(hops, minHops)
                .withMaxDelay(java.time.Duration.ofMillis(cfg.getInt("noray.net.onion.delay.ms", 50)));
        var identity = com.redactado.noray.net.onion.OnionIdentity.generate();
        var carrier = new com.redactado.noray.net.onion.MeshOnionTransport(mesh, identity);
        var onion = new com.redactado.noray.net.onion.OnionService(mesh.localId(), identity, carrier,
                new com.redactado.noray.net.onion.RandomPathSelector(), carrier, onionCfg,
                java.time.Clock.systemUTC(), null);
        carrier.startAnnouncing(cfg.getInt("noray.net.onion.announce.s", 30));
        context.getServiceRegistry().register(com.redactado.noray.net.onion.OnionService.class, onion);
        context.getServiceRegistry().register(com.redactado.noray.net.onion.MeshOnionTransport.class, carrier);
        var metrics = com.redactado.noray.kernel.metrics.Metrics.global();
        metrics.gauge("onion.relays.known", () -> carrier.relays().size());
        for (String k : java.util.List.of("sent", "cover", "forwarded", "delivered", "dropped", "invalid", "expired",
                "replayed", "forward-failed")) {
            metrics.gauge("onion." + k, () -> onion.stats().getOrDefault(k, 0L));
        }
        context.registerResource(id(), () -> {
            metrics.removeGauge("onion.relays.known");
            carrier.close();
            onion.close();
        });
    }

    @Override
    public void shutdown(KernelContext context) {
    }
}
