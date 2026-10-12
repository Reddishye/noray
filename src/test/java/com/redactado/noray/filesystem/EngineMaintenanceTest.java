package com.redactado.noray.filesystem;

import com.redactado.noray.data.DataEngine;
import com.redactado.noray.data.GcReport;
import com.redactado.noray.data.placement.DataCluster;
import com.redactado.noray.data.placement.NodeWatch;
import com.redactado.noray.data.placement.SimpleNode;
import com.redactado.noray.data.tier.AccessTracker;
import com.redactado.noray.data.tier.PolicyCatalog;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.api.StorageAdmin;
import com.redactado.noray.filesystem.driver.pool.PoolVolumeDriver;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.vfs.DataBackend;
import com.redactado.noray.filesystem.vfs.DefaultFileSystemService;
import com.redactado.noray.filesystem.vfs.DefaultVirtualFileSystem;
import com.redactado.noray.filesystem.vfs.MaintenanceService;
import com.redactado.noray.filesystem.vfs.Mount;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.filesystem.vfs.journal.NamespaceJournal;
import com.redactado.noray.filesystem.volume.DefaultVolumeManager;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Repair, garbage collection and automatic tiering through the filesystem service, with a controllable clock. */
class EngineMaintenanceTest {

    static final class ManualClock extends Clock {
        volatile long now = 1_000_000_000L;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        void advance(Duration d) { now += d.toMillis(); }
    }

    private final ManualClock clock = new ManualClock();
    private final List<SimpleNode> nodes = new ArrayList<>();
    private final List<InMemoryObjectStore> stores = new ArrayList<>();
    private ExecutorService executor;
    private DefaultVolumeManager volumes;
    private DefaultFileSystemService fs;
    private DataBackend backend;
    private AccessTracker tracker;
    private final AtomicInteger saves = new AtomicInteger();

    private static final VfsPath POOL = VfsPath.parse("/mnt/pool");

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        volumes = new DefaultVolumeManager(new ConcurrentEventBus(executor, (t, ctx) -> { }));
        volumes.registerDriver(new PoolVolumeDriver());
        DefaultVirtualFileSystem vfs = new DefaultVirtualFileSystem();
        for (int i = 0; i < 10; i++) {
            InMemoryObjectStore s = new InMemoryObjectStore();
            stores.add(s);
            nodes.add(new SimpleNode("n" + i, s));
        }
        DataCluster cluster = DataCluster.of(new ArrayList<>(nodes));
        PolicyCatalog catalog = PolicyCatalog.defaults();
        catalog.registerAuto("auto", new PolicyCatalog.AutoProfile("tiered", "archive", 3.0, 0.5, Duration.ofDays(2)));
        tracker = new AccessTracker(clock, Duration.ofDays(7));
        DataEngine engine = new DataEngine(cluster, com.redactado.noray.data.StoragePolicy.tiered());
        backend = new DataBackend(engine, catalog, tracker, cluster, new NodeWatch(clock, Duration.ofMinutes(30)),
                Duration.ZERO, saves::incrementAndGet);
        fs = new DefaultFileSystemService(vfs, volumes, new InMemoryObjectStore(), null, NamespaceJournal.none(),
                p -> true, backend);
        Volume pool = volumes.attach(Metadata.builder().put("kind", "pool").build());
        vfs.mount(new Mount("pool", POOL, pool.id(), ""));
    }

    @AfterEach
    void tearDown() {
        fs.close();
        volumes.close();
        executor.shutdownNow();
    }

    private static byte[] data(long seed) {
        byte[] b = new byte[120_000];
        new Random(seed).nextBytes(b);
        for (int i = 0; i < b.length / 2; i++) b[i] = (byte) ("tier me ".charAt(i % 8));
        return b;
    }

    private VfsPath file(String name, byte[] content) {
        VfsPath f = POOL.resolve(name);
        fs.createFile(f);
        fs.writeFully(f, content);
        return f;
    }

    /**
     * Index of the n-th node by amount held (so tests never "lose" a node that was not used by chance, nor one that
     * holds nothing but a manifest page: every node holds one of those).
     */
    private int usedNode(int n) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < stores.size(); i++) {
            if (!stores.get(i).listIds().isEmpty()) order.add(i);
        }
        order.sort(Comparator.<Integer>comparingInt(i -> -stores.get(i).listIds().size()).thenComparingInt(i -> i));
        if (order.size() <= n) {
            throw new AssertionError("fewer than " + (n + 1) + " nodes hold data");
        }
        return order.get(n);
    }

    private int totalBlobs() {
        return stores.stream().mapToInt(s -> s.listIds().size()).sum();
    }

    // ── repair ─────────────────────────────────────────────────────────────────

    @Test
    void repairWaitsForTheGracePeriodThenRestoresRedundancy() {
        byte[] content = data(1);
        VfsPath f = file("a.bin", content);
        nodes.get(usedNode(0)).setAvailable(false);
        nodes.get(usedNode(1)).setAvailable(false);

        fs.observeNodes();
        var early = fs.repair(false, false);
        assertEquals(0, early.filesRepaired(), "a node that has been down for a moment is not lost yet");

        clock.advance(Duration.ofMinutes(31));
        fs.observeNodes();
        var late = fs.repair(false, false);
        assertEquals(1, late.files());
        assertEquals(1, late.filesRepaired());
        assertEquals(0, late.chunksLost());
        assertArrayEquals(content, fs.readFully(f));
        assertEquals(0, fs.repair(false, false).filesRepaired(), "second pass finds nothing to do");
    }

    @Test
    void operatorCanForceRepairWithoutWaiting() {
        byte[] content = data(2);
        VfsPath f = file("b.bin", content);
        nodes.get(usedNode(0)).setAvailable(false);
        assertEquals(1, fs.repair(false, true).filesRepaired());
        assertArrayEquals(content, fs.readFully(f));
    }

    @Test
    void repairedManifestSurvivesInTheJournalSnapshot() {
        byte[] content = data(3);
        VfsPath f = file("c.bin", content);
        nodes.get(usedNode(0)).setAvailable(false);
        fs.repair(false, true);
        fs.compact();
        assertArrayEquals(content, fs.readFully(f));
    }

    // ── garbage collection ─────────────────────────────────────────────────────

    @Test
    void gcRemovesOrphansAndKeepsEveryLiveFile() {
        byte[] a = data(4), b = data(5);
        VfsPath fa = file("a.bin", a);
        VfsPath fb = file("b.bin", b);
        for (int i = 0; i < 20; i++) stores.get(i % 10).put(("orphan " + i).getBytes());
        int before = totalBlobs();

        GcReport dry = fs.gc(true);
        assertEquals(20, dry.blobsDeleted());
        assertEquals(before, totalBlobs());

        GcReport real = fs.gc(false);
        assertEquals(20, real.blobsDeleted());
        assertEquals(before - 20, totalBlobs());
        assertArrayEquals(a, fs.readFully(fa));
        assertArrayEquals(b, fs.readFully(fb));
        assertEquals(0, fs.gc(false).blobsDeleted());
    }

    @Test
    void gcRefusesToRunWhenTheLiveSetCannotBeEstablished() {
        file("a.bin", data(6));
        stores.get(0).put("orphan".getBytes());
        int before = totalBlobs();
        nodes.forEach(n -> n.setAvailable(false)); // no manifest can be read now
        FsException e = assertThrows(FsException.class, () -> fs.gc(false));
        assertEquals(FsErrorCode.VOLUME_UNAVAILABLE, e.code());
        nodes.forEach(n -> n.setAvailable(true));
        assertEquals(before, totalBlobs(), "nothing may be deleted on a guess");
    }

    // ── automatic tiering ──────────────────────────────────────────────────────

    @Test
    void idleFilesAreDemotedAndBusyOnesPromotedWithHysteresis() {
        fs.setPolicy(POOL, "auto");
        byte[] content = data(7);
        VfsPath f = file("report.bin", content);
        assertEquals("tiered", fs.storageClassOf(f));

        assertEquals(0, fs.autoTier().demoted(), "too soon: nothing was observed long enough");

        clock.advance(Duration.ofDays(3));
        var demote = fs.autoTier();
        assertEquals(1, demote.demoted());
        assertEquals("archive", fs.storageClassOf(f));
        assertArrayEquals(content, fs.readFully(f));

        // a few reads keep it between the thresholds: it stays cold (no flapping)
        for (int i = 0; i < 9; i++) fs.readFully(f);
        var stay = fs.autoTier();
        assertEquals(0, stay.promoted() + stay.demoted());
        assertEquals("archive", fs.storageClassOf(f));

        // sustained use promotes it
        for (int i = 0; i < 60; i++) fs.readFully(f);
        assertEquals(1, fs.autoTier().promoted());
        assertEquals("tiered", fs.storageClassOf(f));
        assertArrayEquals(content, fs.readFully(f));
    }

    @Test
    void onlyAutoDirectoriesAreEvaluatedAndRecentUseProtects() {
        VfsPath plainDir = POOL.resolve("plain");
        VfsPath autoDir = POOL.resolve("auto");
        fs.mkdir(plainDir);
        fs.mkdir(autoDir);
        fs.setPolicy(autoDir, "auto");
        VfsPath plain = file("plain/p.bin", data(8));
        VfsPath idle = file("auto/idle.bin", data(9));
        VfsPath touched = file("auto/touched.bin", data(12));

        clock.advance(Duration.ofDays(30));
        fs.readFully(touched); // used just now
        var run = fs.autoTier();
        assertEquals(2, run.evaluated(), "the file under the plain directory is not evaluated");
        assertEquals(1, run.demoted());
        assertEquals("archive", fs.storageClassOf(idle));
        assertEquals("tiered", fs.storageClassOf(touched), "recent use protects it");
        assertEquals("tiered", fs.storageClassOf(plain));
    }

    @Test
    void explicitPolicyChangesAreAppliedOnRequest() {
        VfsPath f = file("p.bin", data(10));
        fs.setPolicy(POOL, "archive");
        assertEquals("tiered", fs.storageClassOf(f), "changing a policy never rewrites data by itself");
        assertEquals(1, fs.applyPolicy(POOL));
        assertEquals("archive", fs.storageClassOf(f));
    }

    // ── maintenance loop ───────────────────────────────────────────────────────

    @Test
    void maintenanceCycleObservesRepairsTiersAndSaves() {
        fs.setPolicy(POOL, "auto");
        byte[] content = data(11);
        VfsPath f = file("m.bin", content);
        try (MaintenanceService m = new MaintenanceService(fs, backend, Duration.ofHours(1), true, 1)) {
            int victim = usedNode(0);
            nodes.get(victim).setAvailable(false);
            m.runOnce(); // observes the outage
            clock.advance(Duration.ofDays(3));
            nodes.get(victim).setAvailable(false);
            m.runOnce(); // node lost for long: repaired; file idle: demoted
            assertEquals("archive", fs.storageClassOf(f));
            assertArrayEquals(content, fs.readFully(f));
            assertTrue(saves.get() >= 2);
            assertEquals(0, fs.repair(false, true).filesRepaired(), "the cycle already repaired everything");
        }
    }
}
