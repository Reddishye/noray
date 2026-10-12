package com.redactado.noray.filesystem.store.remote;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.ObjectStore;
import com.redactado.noray.util.SecureFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remote deletes do not destroy data at once: the blob disappears for peers (it is neither served, nor counted, nor
 * listed) and is really removed only after a delay. If a delete was the work of a compromised operator, the owner of
 * this node can bring everything back until the delay runs out ({@link #restore}). A peer that stores the same blob
 * again simply revives it, as blobs are content-addressed. Local deletes by this node itself are not affected.
 */
public final class DeleteQuarantine {

    public record Entry(ObjectId id, long deadlineMillis) {
    }

    private final ObjectStore store;
    private final Clock clock;
    private final Duration delay;
    private final Path file; // may be null: not persisted
    private final Map<ObjectId, Long> deadlines = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<ObjectId> onRemoved = id -> { };

    /** Told about every blob that is really deleted by a sweep. */
    public void onRemoved(java.util.function.Consumer<ObjectId> listener) {
        this.onRemoved = listener;
    }

    public DeleteQuarantine(ObjectStore store, Clock clock, Duration delay, Path file) {
        this.store = store;
        this.clock = clock;
        this.delay = delay;
        this.file = file;
        load();
    }

    public Duration delay() {
        return delay;
    }

    public boolean isHidden(ObjectId id) {
        return deadlines.containsKey(id);
    }

    /** Hides the blob now and schedules its removal. @throws FsException NOT_FOUND if it is absent or already hidden */
    public synchronized void quarantine(ObjectId id) throws FsException {
        if (deadlines.containsKey(id) || !store.has(id)) {
            throw new FsException(FsErrorCode.NOT_FOUND, "no such blob");
        }
        deadlines.put(id, clock.millis() + delay.toMillis());
        persist();
    }

    /** The blob was stored again: it is live, whatever was scheduled. */
    public synchronized void revive(ObjectId id) {
        if (deadlines.remove(id) != null) {
            persist();
        }
    }

    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        deadlines.forEach((id, d) -> out.add(new Entry(id, d)));
        out.sort(Comparator.comparing(e -> e.id().hex()));
        return out;
    }

    /** Brings one blob back. Returns false if it was not quarantined (or is already gone). */
    public synchronized boolean restore(ObjectId id) {
        boolean was = deadlines.remove(id) != null;
        if (was) {
            persist();
        }
        return was;
    }

    /** Brings everything back; returns how many blobs. */
    public synchronized int restoreAll() {
        int n = deadlines.size();
        deadlines.clear();
        persist();
        return n;
    }

    /** Really deletes blobs whose delay has run out; returns how many. */
    public synchronized int sweep() {
        long now = clock.millis();
        int removed = 0;
        for (Map.Entry<ObjectId, Long> e : new ArrayList<>(deadlines.entrySet())) {
            if (e.getValue() <= now) {
                try {
                    store.delete(e.getKey());
                } catch (FsException ex) {
                    if (ex.code() != FsErrorCode.NOT_FOUND) {
                        continue; // try again at the next sweep
                    }
                }
                deadlines.remove(e.getKey());
                onRemoved.accept(e.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            persist();
        }
        return removed;
    }

    private void persist() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            StringBuilder sb = new StringBuilder();
            deadlines.forEach((id, d) -> sb.append(id.hex()).append(' ').append(d).append('\n'));
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            SecureFiles.createOwnerOnly(tmp);
            Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "cannot persist the delete quarantine: " + e.getMessage(), e);
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.trim().split(" ");
                if (p.length == 2) {
                    deadlines.put(new ObjectId(p[0]), Long.parseLong(p[1]));
                }
            }
        } catch (IOException | RuntimeException e) {
            // refusing to boot beats forgetting what was scheduled (or what must stay hidden)
            throw new IllegalStateException("cannot read the delete quarantine " + file + ": " + e.getMessage(), e);
        }
    }
}
