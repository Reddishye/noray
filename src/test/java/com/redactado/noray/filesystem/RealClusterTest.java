package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.api.StorageAdmin;
import com.redactado.noray.filesystem.config.FsConfigService;
import com.redactado.noray.filesystem.vfs.Mount;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.filesystem.vfs.VirtualFileSystem;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.filesystem.volume.VolumeHealthMonitor;
import com.redactado.noray.kernel.core.NorayKernel;
import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.net.NetModule;
import com.redactado.noray.net.NodeId;
import com.redactado.noray.net.PeerCapabilities;
import com.redactado.noray.net.enroll.EnrollmentRecord;
import com.redactado.noray.net.enroll.EnrollmentStore;
import com.redactado.noray.net.overlay.StaticKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The product as it will run: one client node and several storage nodes, each a complete kernel with real UDP sockets
 * and a real handshake. The client stores files in a pool made of its peers' disks; nodes then disappear.
 */
class RealClusterTest {

    @TempDir Path tmp;

    private final List<NorayKernel> kernels = new ArrayList<>();

    @AfterEach
    void stop() {
        for (NorayKernel k : kernels) {
            try { k.shutdown(); } catch (RuntimeException ignored) { }
        }
    }

    private static int freePort() throws Exception {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }

    private record Node(String name, int port, NorayKernel kernel) {
        MeshTransport transport() { return kernel.getContext().getServiceRegistry().require(MeshTransport.class); }
        NodeId id() { return transport().localId(); }
    }

    private Node boot(String name, String passphrase) throws Exception {
        int port = freePort();
        Map<String, String> props = new HashMap<>();
        props.put("noray.data.dir", tmp.resolve(name).toString());
        props.put("noray.net.udp.port", String.valueOf(port));
        props.put("noray.net.mesh.psk", "cd".repeat(32));
        props.put("noray.keyring.argon2.insecure", "true");
        props.put("noray.keyring.argon2.memory.kib", "64");
        props.put("noray.keyring.argon2.iterations", "1");
        props.put("noray.fs.namespace.sync", "false");
        if (passphrase != null) props.put("noray.keyring.passphrase", passphrase);
        NorayKernel k = new NorayKernel(props);
        k.registerModule(new NetModule());
        k.registerModule(new FsModule());
        k.boot();
        kernels.add(k);
        return new Node(name, port, k);
    }

    /** Each side knows the other's keys and where to find it (what an operator does with an enrollment). */
    private static void introduce(Node a, Node b) {
        for (Node[] pair : new Node[][]{{a, b}, {b, a}}) {
            var reg = pair[0].kernel().getContext().getServiceRegistry();
            StaticKeys keys = pair[1].kernel().getContext().getServiceRegistry().require(StaticKeys.class);
            reg.require(EnrollmentStore.class).save(new EnrollmentRecord(pair[1].id(), true, false, Set.of(),
                    "127.0.0.1:" + pair[1].port(), keys.x25519PubHex(), keys.mlkemPubHex()));
            pair[0].transport().enroll(pair[1].id(), new PeerCapabilities(true, false, Set.of()));
        }
    }

    private Volume buildPool(Node client, List<Node> storage) {
        var registry = client.kernel().getContext().getServiceRegistry();
        FsConfigService cfg = registry.require(FsConfigService.class);
        int i = 0;
        for (Node s : storage) {
            cfg.addVolume(Metadata.builder().put("kind", "remote").put("uri", "noray://" + s.id().hex())
                    .put("engine", "true").put("domain", "node" + i++).build());
        }
        Volume pool = cfg.addVolume(Metadata.builder().put("kind", "pool").put("policy", "tiered").build());
        registry.require(VirtualFileSystem.class).mount(new Mount("pool", VfsPath.parse("/mnt/pool"), pool.id(), ""));
        return pool;
    }

    private static byte[] sample(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        for (int i = 0; i < n / 2; i++) b[i] = (byte) ("noray cluster ".charAt(i % 14));
        return b;
    }

    private long blobBytes(Node n) throws Exception {
        Path dir = tmp.resolve(n.name());
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).filter(p -> p.toString().contains("/fs/blobs/"))
                    .mapToLong(p -> p.toFile().length()).sum();
        }
    }

    @Test
    void aFileStoredAcrossRealNodesSurvivesTheLossOfOne() throws Exception {
        Node client = boot("client", "correct horse");
        List<Node> storage = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Node s = boot("s" + i, null);
            introduce(client, s);
            storage.add(s);
        }
        buildPool(client, storage);

        FileSystemService fs = client.kernel().getContext().getServiceRegistry().require(FileSystemService.class);
        VfsPath dir = VfsPath.parse("/mnt/pool/docs");
        VfsPath file = VfsPath.parse("/mnt/pool/docs/report.bin");
        byte[] data = sample(3_000_000, 5);
        fs.mkdir(dir);
        fs.createFile(file);
        fs.writeFully(file, data);
        assertArrayEquals(data, fs.readFully(file), "read back through the network");

        // the data really lives on the other machines, and none of them holds the file in the clear
        int holders = 0;
        for (Node s : storage) {
            if (blobBytes(s) > 0) holders++;
        }
        assertTrue(holders >= 3, "spread over several nodes: " + holders);

        // one node vanishes
        Node lost = storage.stream().filter(s -> {
            try { return blobBytes(s) > 0; } catch (Exception e) { return false; }
        }).findFirst().orElseThrow();
        lost.kernel().shutdown();
        client.kernel().getContext().getServiceRegistry().require(VolumeHealthMonitor.class).checkNow();
        client.kernel().getContext().getServiceRegistry().require(VolumeHealthMonitor.class).checkNow();

        assertArrayEquals(data, fs.readFully(file), "still readable with one node gone");
    }

    private List<Node> holders(List<Node> storage) throws Exception {
        List<Node> out = new ArrayList<>();
        for (Node s : storage) if (blobBytes(s) > 0) out.add(s);
        return out;
    }

    private List<Node> cluster(Node client, int n) throws Exception {
        List<Node> storage = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Node s = boot("s" + i, null);
            introduce(client, s);
            storage.add(s);
        }
        return storage;
    }

    @Test
    void afterLosingNodesRepairRestoresRedundancyAndMoreNodesCanThenFail() throws Exception {
        Node client = boot("client", "correct horse");
        List<Node> storage = cluster(client, 10);
        buildPool(client, storage);
        var registry = client.kernel().getContext().getServiceRegistry();
        FileSystemService fs = registry.require(FileSystemService.class);
        StorageAdmin admin = registry.require(StorageAdmin.class);

        VfsPath file = VfsPath.parse("/mnt/pool/big.bin");
        byte[] data = sample(2_000_000, 8);
        fs.createFile(file);
        fs.writeFully(file, data);

        List<Node> have = holders(storage);
        have.get(0).kernel().shutdown();
        have.get(1).kernel().shutdown();
        VolumeHealthMonitor health = registry.require(VolumeHealthMonitor.class);
        health.checkNow(); // what the monitor does every few seconds: notice who stopped answering
        assertArrayEquals(data, fs.readFully(file), "two of ten gone, k=4 of 8 pieces needed");

        var summary = admin.repair(false, true);
        assertEquals(0, summary.chunksLost());
        assertEquals(0, summary.filesFailed());
        assertTrue(summary.piecesRebuilt() + summary.pagesRestored() > 0, "redundancy was restored: " + summary);

        have.get(2).kernel().shutdown();
        have.get(3).kernel().shutdown();
        health.checkNow();
        assertArrayEquals(data, fs.readFully(file), "and four are gone now");
    }

    @Test
    void storageNodesKeepWhatTheyHoldAcrossARestart() throws Exception {
        Node client = boot("client", "correct horse");
        List<Node> storage = cluster(client, 9);
        buildPool(client, storage);
        FileSystemService fs = client.kernel().getContext().getServiceRegistry().require(FileSystemService.class);
        VfsPath file = VfsPath.parse("/mnt/pool/keep.bin");
        byte[] data = sample(1_000_000, 4);
        fs.createFile(file);
        fs.writeFully(file, data);

        // every storage node restarts on its own disk; the client has not changed
        List<Node> restarted = new ArrayList<>();
        for (Node s : storage) {
            s.kernel().shutdown();
            Node again = bootAt(s.name(), s.port());
            introduce(client, again);
            restarted.add(again);
        }
        client.kernel().getContext().getServiceRegistry().require(VolumeHealthMonitor.class).checkNow();
        assertArrayEquals(data, fs.readFully(file));
    }

    @Test
    void theClientCanRestartAndFindItsFilesAgain() throws Exception {
        Node client = boot("client", "correct horse");
        List<Node> storage = cluster(client, 9);
        buildPool(client, storage);
        FileSystemService fs = client.kernel().getContext().getServiceRegistry().require(FileSystemService.class);
        VfsPath file = VfsPath.parse("/mnt/pool/again.bin");
        byte[] data = sample(800_000, 6);
        fs.createFile(file);
        fs.writeFully(file, data);
        client.kernel().shutdown();

        Node back = bootAt("client", client.port(), "correct horse");
        FileSystemService fs2 = back.kernel().getContext().getServiceRegistry().require(FileSystemService.class);
        assertArrayEquals(data, fs2.readFully(file), "namespace, keys and peers all came back from disk");
    }

    @Test
    void theHealthMonitorTakesAnUnreachableNodeOfflineAndBringsItBack() throws Exception {
        Node client = boot("client", "correct horse");
        List<Node> storage = cluster(client, 2);
        var registry = client.kernel().getContext().getServiceRegistry();
        FsConfigService cfg = registry.require(FsConfigService.class);
        Volume v = cfg.addVolume(Metadata.builder().put("kind", "remote").put("uri", "noray://" + storage.get(0).id().hex()).build());
        VolumeHealthMonitor health = registry.require(VolumeHealthMonitor.class);

        health.checkNow();
        assertEquals(com.redactado.noray.filesystem.volume.VolumeState.ONLINE, v.state());

        storage.get(0).kernel().shutdown();
        health.checkNow();
        assertEquals(com.redactado.noray.filesystem.volume.VolumeState.OFFLINE, v.state());

        Node again = bootAt(storage.get(0).name(), storage.get(0).port());
        introduce(client, again);
        health.checkNow();
        assertEquals(com.redactado.noray.filesystem.volume.VolumeState.ONLINE, v.state(), "answers again after its restart");
    }

    /**
     * Nodes die and come back at random while files are written, repaired and read. After every round every file ever
     * written must read back intact. Seeded, so a failure repeats ({@code -Dnoray.chaos.seed=N}).
     */
    @Test
    void randomNodeFailuresAndRecoveriesNeverLoseOrCorruptAFile() throws Exception {
        long seed = Long.getLong("noray.chaos.seed", 20260101L);
        Random rnd = new Random(seed);
        int rounds = Integer.getInteger("noray.chaos.rounds", 10);
        Node client = boot("client", "correct horse");
        List<Node> storage = cluster(client, 10);
        buildPool(client, storage);
        var registry = client.kernel().getContext().getServiceRegistry();
        FileSystemService fs = registry.require(FileSystemService.class);
        StorageAdmin admin = registry.require(StorageAdmin.class);
        VolumeHealthMonitor health = registry.require(VolumeHealthMonitor.class);

        Map<VfsPath, byte[]> written = new LinkedHashMap<>();
        Set<Integer> dead = new HashSet<>();
        String log = "seed " + seed;
        for (int round = 0; round < rounds; round++) {
            int action = rnd.nextInt(10);
            if (action < 4 && storage.size() - dead.size() > 7) {
                int victim;
                do { victim = rnd.nextInt(storage.size()); } while (dead.contains(victim));
                storage.get(victim).kernel().shutdown();
                dead.add(victim);
                log += " | kill s" + victim;
                health.checkNow();
            } else if (action < 6 && !dead.isEmpty()) {
                int back = dead.stream().skip(rnd.nextInt(dead.size())).findFirst().orElseThrow();
                Node again = bootAt(storage.get(back).name(), storage.get(back).port());
                introduce(client, again);
                storage.set(back, again);
                dead.remove(back);
                log += " | restart s" + back;
                health.checkNow();
            } else if (action < 9) {
                VfsPath f = VfsPath.parse("/mnt/pool/chaos-" + round + ".bin");
                byte[] data = sample(50_000 + rnd.nextInt(900_000), rnd.nextLong());
                fs.createFile(f);
                fs.writeFully(f, data);
                written.put(f, data);
                log += " | write " + data.length;
            } else {
                var r = admin.repair(false, true);
                log += " | repair lost=" + r.chunksLost() + " failed=" + r.filesFailed();
                assertEquals(0, r.chunksLost(), log);
            }
            for (var e : written.entrySet()) {
                assertArrayEquals(e.getValue(), fs.readFully(e.getKey()), "round " + round + ": " + e.getKey() + " | " + log);
            }
        }
        // everybody comes back, the cluster heals, and everything is still there
        for (int d : new ArrayList<>(dead)) {
            Node again = bootAt(storage.get(d).name(), storage.get(d).port());
            introduce(client, again);
            storage.set(d, again);
        }
        health.checkNow();
        var r = admin.repair(false, true);
        assertEquals(0, r.chunksLost(), log);
        assertEquals(0, r.filesFailed(), log);
        for (var e : written.entrySet()) {
            assertArrayEquals(e.getValue(), fs.readFully(e.getKey()), "after full recovery: " + e.getKey() + " | " + log);
        }
        System.out.println("chaos: " + written.size() + " files, " + log);
    }

    private Node bootAt(String name, int port) throws Exception {
        return bootAt(name, port, null);
    }

    private Node bootAt(String name, int port, String passphrase) throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put("noray.data.dir", tmp.resolve(name).toString());
        props.put("noray.net.udp.port", String.valueOf(port));
        props.put("noray.net.mesh.psk", "cd".repeat(32));
        props.put("noray.keyring.argon2.insecure", "true");
        props.put("noray.keyring.argon2.memory.kib", "64");
        props.put("noray.keyring.argon2.iterations", "1");
        props.put("noray.fs.namespace.sync", "false");
        if (passphrase != null) props.put("noray.keyring.passphrase", passphrase);
        NorayKernel k = new NorayKernel(props);
        k.registerModule(new NetModule());
        k.registerModule(new FsModule());
        k.boot();
        kernels.add(k);
        return new Node(name, port, k);
    }
}
