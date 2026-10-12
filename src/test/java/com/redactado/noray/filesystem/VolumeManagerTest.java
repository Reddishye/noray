package com.redactado.noray.filesystem;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.volume.*;
import com.redactado.noray.filesystem.volume.event.VolumeAttached;
import com.redactado.noray.filesystem.volume.event.VolumeDetached;
import com.redactado.noray.filesystem.volume.event.VolumeStateChanged;
import com.redactado.noray.kernel.api.Event;
import com.redactado.noray.kernel.api.EventListener;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class VolumeManagerTest {
    private ExecutorService executor;
    private ConcurrentEventBus eventBus;
    private DefaultVolumeManager volumeManager;
    private List<Event> receivedEvents;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        eventBus = new ConcurrentEventBus(executor, (t, ctx) -> {});
        receivedEvents = Collections.synchronizedList(new ArrayList<>());

        eventBus.subscribe(VolumeAttached.class, receivedEvents::add);
        eventBus.subscribe(VolumeDetached.class, receivedEvents::add);
        eventBus.subscribe(VolumeStateChanged.class, receivedEvents::add);

        volumeManager = new DefaultVolumeManager(eventBus);
        volumeManager.registerDriver(new InMemoryVolumeDriver());
    }

    @AfterEach
    void tearDown() {
        volumeManager.close();
        executor.shutdownNow();
    }

    @Test
    void attachMemoryVolumeTransitionsToOnlineAndPublishesEvents() {
        Metadata metadata = Metadata.builder().put("kind", "memory").build();
        Volume volume = volumeManager.attach(metadata);

        assertNotNull(volume);
        assertEquals(VolumeState.ONLINE, volume.state());
        assertEquals(VolumeKind.MEMORY, volume.kind());
        assertTrue(volumeManager.get(volume.id()).isPresent());
        assertTrue(volumeManager.list().contains(volume));

        boolean hasAttachedEvent = receivedEvents.stream()
                .anyMatch(e -> e instanceof VolumeAttached va && va.id().equals(volume.id()));
        assertTrue(hasAttachedEvent, "VolumeAttached event should have been published");
    }

    @Test
    void attachSameUuidTwiceIsIdempotent() {
        UUID uuid = UUID.randomUUID();
        Metadata metadata = Metadata.builder()
                .put("kind", "memory")
                .put("uuid", uuid.toString())
                .build();

        Volume first = volumeManager.attach(metadata);
        Volume second = volumeManager.attach(metadata);

        assertSame(first, second);
        assertEquals(1, volumeManager.list().size());
        assertEquals(first.id(), new VolumeId(uuid));
    }

    @Test
    void detachRemovesVolumeFromListAndGetReturnsEmpty() {
        Metadata metadata = Metadata.builder().put("kind", "memory").build();
        Volume volume = volumeManager.attach(metadata);
        VolumeId id = volume.id();

        volumeManager.detach(id);

        assertTrue(volumeManager.get(id).isEmpty());
        assertFalse(volumeManager.list().contains(volume));

        boolean hasDetachedEvent = receivedEvents.stream()
                .anyMatch(e -> e instanceof VolumeDetached vd && vd.id().equals(id));
        assertTrue(hasDetachedEvent, "VolumeDetached event should have been published");
    }

    @Test
    void detachUnknownVolumeThrowsVolumeUnknown() {
        VolumeId unknown = new VolumeId(UUID.randomUUID());
        FsException ex = assertThrows(FsException.class, () -> volumeManager.detach(unknown));
        assertEquals(FsErrorCode.VOLUME_UNKNOWN, ex.code());
    }

    @Test
    void markOfflineOnOnlineSetsOfflineThenDetachSetsDetached() {
        Metadata metadata = Metadata.builder().put("kind", "memory").build();
        Volume volume = volumeManager.attach(metadata);
        VolumeId id = volume.id();

        volumeManager.markOffline(id, "Transport unplugged");

        assertEquals(VolumeState.OFFLINE, volume.state());
        assertTrue(volumeManager.get(id).isPresent());

        volumeManager.detach(id);

        assertTrue(volumeManager.get(id).isEmpty());
        assertEquals(VolumeState.DETACHED, volume.state());
    }
}
