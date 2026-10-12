package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.replica.*;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.PutResult;
import com.redactado.noray.filesystem.volume.*;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ReplicaPlannerTest {
    private ExecutorService executor;
    private ConcurrentEventBus eventBus;
    private DefaultVolumeManager volumeManager;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        eventBus = new ConcurrentEventBus(executor, (t, ctx) -> {});
        volumeManager = new DefaultVolumeManager(eventBus);
        volumeManager.registerDriver(new InMemoryVolumeDriver());
    }

    @AfterEach
    void tearDown() {
        volumeManager.close();
        executor.shutdownNow();
    }

    @Test
    void quorumPutAndGetWithThreeReplicas() {
        ReplicaGroupId groupId = new ReplicaGroupId(UUID.randomUUID());
        QuorumSpec spec = new QuorumSpec(3, 2, 2);
        DefaultReplicaPlanner planner = new DefaultReplicaPlanner(volumeManager, spec, false);

        Metadata meta1 = Metadata.builder().put("kind", "memory").put("replica-group", groupId.toString()).build();
        Metadata meta2 = Metadata.builder().put("kind", "memory").put("replica-group", groupId.toString()).build();
        Metadata meta3 = Metadata.builder().put("kind", "memory").put("replica-group", groupId.toString()).build();

        Volume v1 = volumeManager.attach(meta1);
        Volume v2 = volumeManager.attach(meta2);
        Volume v3 = volumeManager.attach(meta3);

        byte[] data = "Replicated Noray Data".getBytes(StandardCharsets.UTF_8);
        PutResult res = planner.put(groupId, data);
        assertNotNull(res);
        ObjectId id = res.id();

        // At least 2 stores must have the object
        int hasCount = (v1.store().has(id) ? 1 : 0) + (v2.store().has(id) ? 1 : 0) + (v3.store().has(id) ? 1 : 0);
        assertTrue(hasCount >= 2, "At least 2 replicas must store the blob");

        // Detach one replica, put still succeeds (w=2, 2 available)
        volumeManager.detach(v3.id());
        byte[] data2 = "Second Replicated Blob".getBytes(StandardCharsets.UTF_8);
        PutResult res2 = planner.put(groupId, data2);
        assertNotNull(res2);

        // Detach another replica, put fails with QUORUM_FAILED (1 available < w=2)
        volumeManager.detach(v2.id());
        byte[] data3 = "Third Replicated Blob".getBytes(StandardCharsets.UTF_8);
        FsException ex = assertThrows(FsException.class, () -> planner.put(groupId, data3));
        assertEquals(FsErrorCode.QUORUM_FAILED, ex.code());

        // With allow_w1=true, put succeeds with 1 replica
        DefaultReplicaPlanner plannerW1 = new DefaultReplicaPlanner(volumeManager, spec, true);
        PutResult resW1 = plannerW1.put(groupId, data3);
        assertNotNull(resW1);
        assertTrue(v1.store().has(resW1.id()));
    }

    @Test
    void repairSynchronizesMissingObjectsAcrossReplicas() {
        ReplicaGroupId groupId = new ReplicaGroupId(UUID.randomUUID());
        QuorumSpec spec = new QuorumSpec(3, 2, 2);
        DefaultReplicaPlanner planner = new DefaultReplicaPlanner(volumeManager, spec, false);

        Metadata meta1 = Metadata.builder().put("kind", "memory").put("replica-group", groupId.toString()).build();
        Metadata meta2 = Metadata.builder().put("kind", "memory").put("replica-group", groupId.toString()).build();

        Volume v1 = volumeManager.attach(meta1);
        Volume v2 = volumeManager.attach(meta2);

        byte[] data = "Data to repair".getBytes(StandardCharsets.UTF_8);
        PutResult res = planner.put(groupId, data);
        ObjectId id = res.id();

        // Attach a third empty volume in the same replica group
        Metadata meta3 = Metadata.builder().put("kind", "memory").put("replica-group", groupId.toString()).build();
        Volume v3 = volumeManager.attach(meta3);

        assertFalse(v3.store().has(id));

        planner.repair(groupId);

        assertTrue(v3.store().has(id));
        assertArrayEquals(data, v3.store().get(id));
    }

    @Test
    void getAfterW2EvenIfThirdEmpty() {
        ReplicaGroupId groupId = new ReplicaGroupId(UUID.randomUUID());
        QuorumSpec spec = new QuorumSpec(3, 2, 2);
        DefaultReplicaPlanner planner = new DefaultReplicaPlanner(volumeManager, spec, false);

        Metadata meta1 = Metadata.builder().put("kind", "memory").put("replica-group", groupId.toString()).build();
        Metadata meta2 = Metadata.builder().put("kind", "memory").put("replica-group", groupId.toString()).build();
        Metadata meta3 = Metadata.builder().put("kind", "memory").put("replica-group", groupId.toString()).build();

        Volume v1 = volumeManager.attach(meta1);
        Volume v2 = volumeManager.attach(meta2);
        Volume v3 = volumeManager.attach(meta3);

        byte[] data = "Quorum Get Test".getBytes(StandardCharsets.UTF_8);
        // Put directly only into v1 and v2
        PutResult pr = v1.store().put(data);
        v2.store().put(data);
        assertFalse(v3.store().has(pr.id()));

        byte[] retrieved = planner.get(groupId, pr.id());
        assertArrayEquals(data, retrieved);
    }
}
