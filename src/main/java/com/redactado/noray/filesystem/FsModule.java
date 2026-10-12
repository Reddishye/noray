package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.replica.ReplicaPlanner;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.store.LocalDirObjectStore;
import com.redactado.noray.filesystem.store.ObjectStore;
import com.redactado.noray.filesystem.api.FileSystemFramework;
import com.redactado.noray.filesystem.internal.DefaultFileSystemFramework;
import com.redactado.noray.filesystem.store.remote.BlobResponder;
import com.redactado.noray.filesystem.store.remote.DeleteQuarantine;
import com.redactado.noray.identity.AccessPolicy;
import com.redactado.noray.identity.IdentityBootstrap;
import com.redactado.noray.identity.IdentityService;
import com.redactado.noray.identity.TransportEnroller;
import com.redactado.noray.net.enroll.EnrollmentStore;
import com.redactado.noray.net.overlay.StaticKeys;
import com.redactado.noray.filesystem.driver.external.DirectoryHotplugService;
import com.redactado.noray.filesystem.driver.external.ExternalWatchVolumeDriver;
import com.redactado.noray.filesystem.vfs.DefaultFileSystemService;
import com.redactado.noray.filesystem.vfs.DefaultVirtualFileSystem;
import com.redactado.noray.filesystem.vfs.VirtualFileSystem;
import com.redactado.noray.filesystem.config.FileFsConfigStore;
import com.redactado.noray.filesystem.config.FsConfigService;
import com.redactado.noray.filesystem.config.FsConfigStore;
import com.redactado.noray.data.DataEngine;
import com.redactado.noray.data.StoragePolicy;
import com.redactado.noray.data.crypto.EncryptedFile;
import com.redactado.noray.data.crypto.Keyring;
import com.redactado.noray.data.crypto.KeyringProvider;
import com.redactado.noray.data.placement.MeasuredCluster;
import com.redactado.noray.data.placement.NodeWatch;
import com.redactado.noray.data.placement.VolumeCluster;
import com.redactado.noray.data.tier.AccessTracker;
import com.redactado.noray.data.tier.PolicyCatalog;
import com.redactado.noray.filesystem.api.StorageAdmin;
import com.redactado.noray.filesystem.driver.local.LocalVolumeDriver;
import com.redactado.noray.filesystem.driver.pool.PoolVolumeDriver;
import com.redactado.noray.filesystem.vfs.DataBackend;
import com.redactado.noray.filesystem.vfs.MaintenanceService;
import com.redactado.noray.filesystem.vfs.journal.PayloadCipher;
import com.redactado.noray.filesystem.vfs.journal.FileNamespaceJournal;
import com.redactado.noray.filesystem.vfs.journal.NamespaceJournal;
import com.redactado.noray.filesystem.vfs.journal.NotifyingJournal;
import com.redactado.noray.identity.witness.WitnessService;
import com.redactado.noray.filesystem.volume.DefaultVolumeManager;
import com.redactado.noray.filesystem.volume.InMemoryVolumeDriver;
import com.redactado.noray.filesystem.volume.VolumeManager;
import com.redactado.noray.kernel.api.InitLevel;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.kernel.api.KernelModule;
import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.net.NodeId;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

public class FsModule implements KernelModule {
    private static final System.Logger LOG = System.getLogger("noray.fs");
    private DefaultVolumeManager volumeManager;

    @Override
    public String id() {
        return "noray.fs";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("noray.net");
    }

    @Override
    public InitLevel getInitLevel() {
        return InitLevel.SUBSYSTEM;
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
        if (context.getConfiguration().get("noray.fs.digest", null) == null) {
            context.getConfiguration().set("noray.fs.digest", "sha256");
        }
        if (context.getConfiguration().get("noray.fs.replica.n", null) == null) {
            context.getConfiguration().set("noray.fs.replica.n", "3");
        }
        if (context.getConfiguration().get("noray.fs.replica.w", null) == null) {
            context.getConfiguration().set("noray.fs.replica.w", "2");
        }
        if (context.getConfiguration().get("noray.fs.replica.r", null) == null) {
            context.getConfiguration().set("noray.fs.replica.r", "2");
        }
        if (context.getConfiguration().get("noray.fs.replica.allow_w1", null) == null) {
            context.getConfiguration().set("noray.fs.replica.allow_w1", "false");
        }

        DefaultVolumeManager manager = new DefaultVolumeManager(context.getEventBus());
        manager.registerDriver(new InMemoryVolumeDriver());
        context.getServiceRegistry().register(VolumeManager.class, manager);
        context.registerResource(id(), manager);
        this.volumeManager = manager;

        DefaultVirtualFileSystem vfs = new DefaultVirtualFileSystem();
        context.getServiceRegistry().register(VirtualFileSystem.class, vfs);

        // What peers store on this node must survive a restart: on disk by default ("memory" is for experiments).
        String localRoot = context.getConfiguration().get("noray.fs.local.root", "");
        ObjectStore store;
        if ("memory".equalsIgnoreCase(localRoot)) {
            store = new InMemoryObjectStore();
        } else if (localRoot != null && !localRoot.isBlank()) {
            store = new LocalDirObjectStore(Path.of(localRoot));
        } else {
            store = new LocalDirObjectStore(Path.of(context.getConfiguration().get("noray.data.dir", "data"))
                    .resolve("fs").resolve("blobs"));
        }
        context.getServiceRegistry().register(ObjectStore.class, store);

        MeshTransport transport = context.getServiceRegistry().require(MeshTransport.class);
        ObjectStore localStore = context.getServiceRegistry().require(ObjectStore.class);

        // The keyring is opened first: the node's identity key and the blob service depend on it.
        Path dataDir = Path.of(context.getConfiguration().get("noray.data.dir", "data"));
        Keyring shared = context.getServiceRegistry().get(Keyring.class).orElse(null); // the network module may have opened it
        Keyring keyring = shared != null ? shared : KeyringProvider.open(context, dataDir);
        if (shared == null && keyring != null) {
            context.registerResource(id(), keyring);
            context.getServiceRegistry().register(Keyring.class, keyring);
        }

        byte[] transportKey = context.getServiceRegistry().get(StaticKeys.class).map(k -> k.x25519Pub).orElse(new byte[0]);
        byte[] kemKey = context.getServiceRegistry().get(StaticKeys.class).map(k -> k.mlkemPub.getEncoded()).orElse(new byte[0]);
        TransportEnroller enroller = new TransportEnroller(transport,
                context.getServiceRegistry().get(EnrollmentStore.class).orElse(null));
        IdentityBootstrap.Managed identity = IdentityBootstrap.create(context.getConfiguration(), dataDir, keyring,
                transport, transportKey, kemKey, enroller, java.time.Clock.systemUTC());
        AccessPolicy accessPolicy;
        if (identity != null) {
            context.getServiceRegistry().register(IdentityService.class, identity.service());
            context.registerResource(id(), identity.exchange());
            context.registerResource(id(), identity.maintenance());
            accessPolicy = identity.policy();
        } else {
            LOG.log(System.Logger.Level.WARNING, "identity: no authority configured (noray.identity.authority.keys); "
                    + "every enrolled peer may read, write, delete and list this node's blobs");
            accessPolicy = AccessPolicy.allowAll();
        }
        long maxBlob = (long) context.getConfiguration().getInt("noray.fs.blob.max.mib", 256) * 1024 * 1024;
        long deleteDelayHours = context.getConfiguration().getInt("noray.fs.blob.delete.delay.h", 24);
        DeleteQuarantine quarantine = deleteDelayHours > 0
                ? new DeleteQuarantine(localStore, java.time.Clock.systemUTC(), java.time.Duration.ofHours(deleteDelayHours),
                        dataDir.resolve("fs").resolve("delete-quarantine"))
                : null;
        BlobResponder responder = new BlobResponder(transport, localStore, accessPolicy, maxBlob, quarantine);
        int peerMibPerMinute = context.getConfiguration().getInt("noray.fs.blob.peer.rate.mib.per.min", 0);
        if (peerMibPerMinute > 0) {
            responder.limitPuts(new com.redactado.noray.filesystem.store.remote.PeerRateLimiter(
                    (long) peerMibPerMinute * 1024 * 1024, java.time.Clock.systemUTC()));
        }
        int peerQuotaMib = context.getConfiguration().getInt("noray.fs.blob.peer.quota.mib", 0);
        if (peerQuotaMib > 0) {
            var quota = new com.redactado.noray.filesystem.store.remote.PeerQuota(
                    (long) peerQuotaMib * 1024 * 1024, dataDir.resolve("fs").resolve("peer-quota"),
                    keyring == null ? null : keyring.subKey("peer-quota"));
            // "<node id>:<MiB>,<node id>:<MiB>": peers that get their own limit
            for (String item : context.getConfiguration().get("noray.fs.blob.peer.quota.overrides", "").split("[,\\s]+")) {
                int c = item.indexOf(':');
                if (c > 0) {
                    quota.override(new com.redactado.noray.net.NodeId(item.substring(0, c).trim()),
                            Long.parseLong(item.substring(c + 1).trim()) * 1024 * 1024);
                }
            }
            responder.quota(quota);
        }
        responder.start();
        context.registerResource(id(), responder);
        context.getServiceRegistry().register(BlobResponder.class, responder);

        manager.registerDriver(new com.redactado.noray.filesystem.driver.remote.RemoteVolumeDriver(transport));

        manager.registerDriver(new ExternalWatchVolumeDriver());
        manager.registerDriver(new LocalVolumeDriver());
        manager.registerDriver(new PoolVolumeDriver());

        // Operator-defined volumes and mounts survive restarts.
        Path fsDir = Path.of(context.getConfiguration().get("noray.data.dir", "data")).resolve("fs");
        FsConfigService fsConfig = new FsConfigService(manager, vfs, openConfigStore(context, fsDir));
        FsConfigService.RestoreReport restored = fsConfig.restore();
        restored.failedVolumes().forEach((id, why) ->
                LOG.log(System.Logger.Level.WARNING, "fs: could not attach volume " + id + ": " + why));
        restored.failedMounts().forEach((m, why) ->
                LOG.log(System.Logger.Level.WARNING, "fs: could not restore mount " + m.mountPoint() + ": " + why));
        context.getServiceRegistry().register(FsConfigService.class, fsConfig);

        String externalWatchPath = context.getConfiguration().get("noray.fs.external.watch", "");
        if (externalWatchPath != null && !externalWatchPath.isBlank()) {
            java.nio.file.Path watchPath = java.nio.file.Path.of(externalWatchPath);
            java.util.UUID watchUuid;
            String uuidStr = context.getConfiguration().get("noray.fs.external.uuid", "");
            if (uuidStr != null && !uuidStr.isBlank()) {
                try {
                    watchUuid = java.util.UUID.fromString(uuidStr);
                } catch (IllegalArgumentException e) {
                    watchUuid = java.util.UUID.randomUUID();
                }
            } else {
                watchUuid = java.util.UUID.randomUUID();
            }
            DirectoryHotplugService hotplug = new DirectoryHotplugService(manager, watchPath, watchUuid, java.time.Duration.ofSeconds(1));
            hotplug.start();
            context.registerResource(id(), hotplug);
        }

        // With a passphrase the namespace journal is encrypted and pool mounts (data engine) become available.
        PayloadCipher cipher = keyring == null ? null : PayloadCipher.aead(keyring.subKey("namespace-journal"));

        ReplicaPlanner replicaPlanner = context.getServiceRegistry().get(ReplicaPlanner.class).orElse(null);
        NamespaceJournal journal = openNamespaceJournal(context, fsDir, cipher);
        final NamespaceJournal baseJournal = journal;
        WitnessService witness = null;
        if (identity != null && context.getConfiguration().getBoolean("noray.witness.enabled", true)) {
            witness = new WitnessService(transport, identity.service().directory(),
                    identity.service().identity().signingKey(), dataDir.resolve("identity").resolve("witness"),
                    keyring == null ? null : keyring.subKey("witness"), java.time.Clock.systemUTC(),
                    context.getConfiguration().getInt("noray.witness.max", 5));
            witness.bind(journal::state);
            identity.service().onRevoked(witness::forget);
            journal = new NotifyingJournal(journal, witness);
            if (context.getConfiguration().getBoolean("noray.witness.enforce", false)) {
                journal = new com.redactado.noray.filesystem.vfs.journal.GuardedJournal(journal, witness::integrityAlarm);
            }
        }
        DataBackend backend = keyring == null ? null : createBackend(context, manager, keyring, fsDir);
        DefaultFileSystemService fsService = new DefaultFileSystemService(vfs, manager, store, replicaPlanner, journal,
                DefaultFileSystemService.durableVolumesOnly(vfs, manager), backend);
        context.registerResource(id(), fsService);
        if (identity != null && keyring != null && context.getConfiguration().getBoolean("noray.vault.enabled", true)) {
            final NamespaceJournal observed = journal;
            com.redactado.noray.filesystem.vfs.journal.NamespaceVault vault =
                    new com.redactado.noray.filesystem.vfs.journal.NamespaceVault(transport,
                            identity.service().directory(), keyring.subKey("namespace-vault"),
                            dataDir.resolve("identity").resolve("vault"), fsService::exportSnapshot, observed::state,
                            context.getConfiguration().getInt("noray.vault.copies", 3));
            vault.bindRestore(baseJournal);
            identity.service().onRevoked(vault::forget);
            vault.start(java.time.Duration.ofSeconds(context.getConfiguration().getInt("noray.vault.interval.s", 60)));
            context.registerResource(id(), vault);
            context.getServiceRegistry().register(com.redactado.noray.filesystem.vfs.journal.NamespaceVault.class, vault);
        }
        if (witness != null) {
            witness.start(java.time.Duration.ofSeconds(context.getConfiguration().getInt("noray.witness.interval.s", 300)));
            context.registerResource(id(), witness);
            context.getServiceRegistry().register(WitnessService.class, witness);
        }
        int healthEvery = context.getConfiguration().getInt("noray.fs.health.interval.s", 10);
        if (healthEvery > 0) {
            com.redactado.noray.filesystem.volume.VolumeHealthMonitor health =
                    new com.redactado.noray.filesystem.volume.VolumeHealthMonitor(manager);
            health.beforeEachRound(() -> {
                FsConfigService.RetryReport r = fsConfig.retryPending();
                r.attached().forEach(v -> LOG.log(System.Logger.Level.INFO, "fs: volume " + v + " attached after all"));
                r.mounted().forEach(m -> LOG.log(System.Logger.Level.INFO, "fs: mount " + m.mountPoint() + " restored after all"));
            });
            health.start(java.time.Duration.ofSeconds(healthEvery));
            context.registerResource(id(), health);
            context.getServiceRegistry().register(com.redactado.noray.filesystem.volume.VolumeHealthMonitor.class, health);
        }
        context.getServiceRegistry().register(FileSystemService.class, fsService);
        context.getServiceRegistry().register(StorageAdmin.class, fsService);
        context.getServiceRegistry().register(com.redactado.noray.filesystem.api.NamespaceAdmin.class, fsService);
        context.getServiceRegistry().get(com.redactado.noray.kernel.capability.CapabilityRegistry.class)
                .ifPresent(r -> r.registerAttenuator(
                        com.redactado.noray.filesystem.capability.ScopedFileSystemService.attenuator()));
        if (backend != null) {
            int everySeconds = context.getConfiguration().getInt("noray.fs.maintenance.interval.s", 0);
            if (everySeconds > 0) {
                MaintenanceService maintenance = new MaintenanceService(fsService, backend,
                        java.time.Duration.ofSeconds(everySeconds),
                        context.getConfiguration().getBoolean("noray.fs.maintenance.gc", false),
                        context.getConfiguration().getInt("noray.fs.maintenance.gc.every", 24));
                maintenance.start();
                context.registerResource(id(), maintenance);
                context.getServiceRegistry().register(MaintenanceService.class, maintenance);
            }
            context.getServiceRegistry().register(DataEngine.class, backend.engine());
            context.getServiceRegistry().register(com.redactado.noray.data.placement.DataCluster.class, backend.cluster());
            context.getServiceRegistry().register(PolicyCatalog.class, backend.catalog());
            context.getServiceRegistry().register(AccessTracker.class, backend.tracker());
        }

        FileSystemFramework fsFw = new DefaultFileSystemFramework(manager, vfs, store, fsService);
        context.getServiceRegistry().register(FileSystemFramework.class, fsFw);
    }

    private static FsConfigStore openConfigStore(KernelContext context, Path fsDir) {
        String explicit = context.getConfiguration().get("noray.fs.config.file", "");
        Path file = explicit != null && !explicit.isBlank() ? Path.of(explicit) : fsDir.resolve("volumes.properties");
        return new FileFsConfigStore(file);
    }

    /**
     * The namespace journal lives in {@code <noray.data.dir>/fs/namespace.journal} (override with
     * {@code noray.fs.namespace.file}). Only entries on volumes that survive a restart are written to it.
     * {@code noray.fs.namespace.sync=false} trades durability for speed (no fsync per change).
     */
    private static NamespaceJournal openNamespaceJournal(KernelContext context, Path fsDir, PayloadCipher cipher) {
        String explicit = context.getConfiguration().get("noray.fs.namespace.file", "");
        Path file = explicit != null && !explicit.isBlank() ? Path.of(explicit) : fsDir.resolve("namespace.journal");
        boolean sync = context.getConfiguration().getBoolean("noray.fs.namespace.sync", true);
        return new FileNamespaceJournal(file, sync, cipher);
    }

    private DataBackend createBackend(KernelContext context, DefaultVolumeManager manager, Keyring keyring, Path fsDir) {
        int fanout = context.getConfiguration().getInt("noray.fs.engine.fanout", DataEngine.DEFAULT_FANOUT);
        int window = context.getConfiguration().getInt("noray.fs.engine.window", DataEngine.DEFAULT_WINDOW);
        MeasuredCluster cluster = new MeasuredCluster(new VolumeCluster(manager));
        DataEngine engine = new DataEngine(cluster, StoragePolicy.tiered(), fanout, window);
        context.registerResource(id(), engine);

        long halfLifeHours = context.getConfiguration().getInt("noray.fs.tier.halflife.hours", 24 * 7);
        AccessTracker tracker = new AccessTracker(java.time.Clock.systemUTC(), java.time.Duration.ofHours(halfLifeHours));
        Path heatFile = fsDir.resolve("heat.bin");
        byte[] heatKey = keyring.subKey("access-heat");
        try {
            EncryptedFile.read(heatFile, heatKey, "noray/heat/v1").ifPresent(tracker::importFrom);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "fs: ignoring unreadable access statistics: " + e.getMessage());
        }
        Runnable saveStats = () -> {
            try {
                EncryptedFile.write(heatFile, heatKey, "noray/heat/v1", tracker.export());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "fs: could not save access statistics: " + e.getMessage());
            }
        };
        context.registerResource(id(), saveStats::run);

        NodeWatch watch = new NodeWatch(java.time.Clock.systemUTC(),
                java.time.Duration.ofMinutes(context.getConfiguration().getInt("noray.fs.node.grace.minutes", 30)));
        java.time.Duration gcGrace = java.time.Duration.ofMinutes(context.getConfiguration().getInt("noray.fs.gc.grace.minutes", 60));
        return new DataBackend(engine, PolicyCatalog.defaults(), tracker, cluster, watch, gcGrace, saveStats);
    }

    @Override
    public void shutdown(KernelContext context) throws Exception {
        if (volumeManager != null) {
            volumeManager.close();
            volumeManager = null;
        }
    }
}