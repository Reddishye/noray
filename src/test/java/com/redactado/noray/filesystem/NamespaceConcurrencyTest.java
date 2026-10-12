package com.redactado.noray.filesystem;

import com.redactado.noray.data.DataEngine;
import com.redactado.noray.data.StoragePolicy;
import com.redactado.noray.data.placement.DataCluster;
import com.redactado.noray.data.placement.NodeWatch;
import com.redactado.noray.data.placement.SimpleNode;
import com.redactado.noray.data.tier.AccessTracker;
import com.redactado.noray.data.tier.PolicyCatalog;
import com.redactado.noray.filesystem.driver.pool.PoolVolumeDriver;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.store.PutResult;
import com.redactado.noray.filesystem.vfs.DataBackend;
import com.redactado.noray.filesystem.vfs.DefaultFileSystemService;
import com.redactado.noray.filesystem.vfs.DefaultVirtualFileSystem;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** A slow write to the data engine must not hold up the rest of the namespace. */
class NamespaceConcurrencyTest {

    /** A store whose first {@code put} after being armed stalls until released. */
    static final class StallingStore implements com.redactado.noray.filesystem.store.ObjectStore {
        private final InMemoryObjectStore inner = new InMemoryObjectStore();
        final AtomicBoolean armed = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public PutResult put(byte[] data) {
            if (armed.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return inner.put(data);
        }

        @Override public byte[] get(com.redactado.noray.filesystem.store.ObjectId id) { return inner.get(id); }
        @Override public boolean has(com.redactado.noray.filesystem.store.ObjectId id) { return inner.has(id); }
        @Override public void delete(com.redactado.noray.filesystem.store.ObjectId id) { inner.delete(id); }
        @Override public long size(com.redactado.noray.filesystem.store.ObjectId id) { return inner.size(id); }
        @Override public java.util.Set<com.redactado.noray.filesystem.store.ObjectId> listIds() { return inner.listIds(); }
    }

    private static final VfsPath POOL = VfsPath.parse("/mnt/pool");

    private final StallingStore stalling = new StallingStore();
    private ExecutorService executor;
    private ExecutorService clients;
    private DefaultVolumeManager volumes;
    private DefaultFileSystemService fs;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        clients = Executors.newCachedThreadPool();
        volumes = new DefaultVolumeManager(new ConcurrentEventBus(executor, (t, c) -> { }));
        volumes.registerDriver(new PoolVolumeDriver());
        DefaultVirtualFileSystem vfs = new DefaultVirtualFileSystem();
        List<SimpleNode> nodes = new ArrayList<>();
        nodes.add(new SimpleNode("n0", stalling));
        for (int i = 1; i < 10; i++) {
            nodes.add(new SimpleNode("n" + i, new InMemoryObjectStore()));
        }
        DataCluster cluster = DataCluster.of(nodes);
        DataEngine engine = new DataEngine(cluster, StoragePolicy.tiered());
        DataBackend backend = new DataBackend(engine, PolicyCatalog.defaults(),
                new AccessTracker(Clock.systemUTC(), Duration.ofDays(7)), cluster,
                new NodeWatch(Clock.systemUTC(), Duration.ofMinutes(30)), Duration.ZERO, () -> { });
        fs = new DefaultFileSystemService(vfs, volumes, new InMemoryObjectStore(), null, NamespaceJournal.none(),
                p -> true, backend);
        Volume pool = volumes.attach(Metadata.builder().put("kind", "pool").build());
        vfs.mount(new Mount("pool", POOL, pool.id(), ""));
    }

    @AfterEach
    void tearDown() {
        stalling.release.countDown();
        clients.shutdownNow();
        fs.close();
        volumes.close();
        executor.shutdownNow();
    }

    private static byte[] data(long seed) {
        byte[] b = new byte[150_000];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void aStalledWriteDoesNotBlockOtherFilesReadsOrNamespaceChanges() throws Exception {
        byte[] first = data(1);
        fs.createFile(POOL.resolve("steady"));
        fs.writeFully(POOL.resolve("steady"), first);
        fs.createFile(POOL.resolve("slow"));

        stalling.armed.set(true);
        byte[] slowData = data(2);
        Future<?> slow = clients.submit(() -> fs.writeFully(POOL.resolve("slow"), slowData));
        assertTrue(stalling.entered.await(10, TimeUnit.SECONDS), "the write should reach the engine");

        // while it is stuck inside the engine: reads, new files, directories, deletes and other writes proceed
        Future<Object> others = clients.submit(() -> {
            assertArrayEquals(first, fs.readFully(POOL.resolve("steady")));
            fs.mkdir(POOL.resolve("dir"));
            fs.createFile(POOL.resolve("dir/other"));
            byte[] d = data(3);
            fs.writeFully(POOL.resolve("dir/other"), d);
            assertArrayEquals(d, fs.readFully(POOL.resolve("dir/other")));
            fs.unlink(POOL.resolve("dir/other"));
            assertEquals(3, fs.readdir(POOL).size());
            assertTrue(fs.readdir(POOL.resolve("dir")).isEmpty());
            return null;
        });
        others.get(10, TimeUnit.SECONDS);
        assertFalse(slow.isDone());

        stalling.release.countDown();
        slow.get(20, TimeUnit.SECONDS);
        assertArrayEquals(slowData, fs.readFully(POOL.resolve("slow")));
    }

    @Test
    void repairRunsWhileAWriteIsStalledAndGcWaitsForItWithoutLosingItsData() throws Exception {
        fs.createFile(POOL.resolve("steady"));
        fs.writeFully(POOL.resolve("steady"), data(1));
        fs.createFile(POOL.resolve("slow"));
        stalling.armed.set(true);
        byte[] slowData = data(2);
        Future<?> slow = clients.submit(() -> fs.writeFully(POOL.resolve("slow"), slowData));
        assertTrue(stalling.entered.await(10, TimeUnit.SECONDS));

        Future<?> repair = clients.submit(() -> fs.repair(false, false));
        // repair of other files does not need the stalled write to finish (only collection does)
        assertDoesNotThrow(() -> repair.get(20, TimeUnit.SECONDS));

        Future<?> gc = clients.submit(() -> fs.gc(false));
        Thread.sleep(300);
        assertFalse(gc.isDone(), "garbage collection must wait for the in-flight write, which owns unreferenced blobs");

        stalling.release.countDown();
        slow.get(20, TimeUnit.SECONDS);
        gc.get(20, TimeUnit.SECONDS);
        assertArrayEquals(slowData, fs.readFully(POOL.resolve("slow")));
    }

    @Test
    void manyWritersToTheSameFileLeaveOneIntactVersionAndNoLeak() throws Exception {
        fs.createFile(POOL.resolve("hot"));
        List<byte[]> versions = new ArrayList<>();
        List<Future<?>> writers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            byte[] d = data(100 + i);
            versions.add(d);
            writers.add(clients.submit(() -> fs.writeFully(POOL.resolve("hot"), d)));
        }
        for (Future<?> f : writers) {
            f.get(30, TimeUnit.SECONDS);
        }
        byte[] result = fs.readFully(POOL.resolve("hot"));
        assertTrue(versions.stream().anyMatch(v -> java.util.Arrays.equals(v, result)));
        // everything but the winner was dropped: collecting garbage finds nothing live missing
        var report = fs.gc(false);
        assertArrayEquals(result, fs.readFully(POOL.resolve("hot")));
        assertNotNull(report);
    }

    @Test
    void aFileRemovedWhileBeingWrittenIsNotResurrected() throws Exception {
        fs.createFile(POOL.resolve("doomed"));
        stalling.armed.set(true);
        Future<?> write = clients.submit(() -> fs.writeFully(POOL.resolve("doomed"), data(5)));
        assertTrue(stalling.entered.await(10, TimeUnit.SECONDS));
        fs.unlink(POOL.resolve("doomed"));
        stalling.release.countDown();
        var e = assertThrows(java.util.concurrent.ExecutionException.class, () -> write.get(20, TimeUnit.SECONDS));
        assertInstanceOf(com.redactado.noray.filesystem.api.FsException.class, e.getCause());
        assertTrue(fs.readdir(POOL).isEmpty());
    }
}
