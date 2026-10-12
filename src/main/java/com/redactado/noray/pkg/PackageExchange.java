package com.redactado.noray.pkg;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.net.MeshChannel;
import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.net.NodeId;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Fetches a package from one's own peers by the SHA-256 of its payload (M5). Only enrolled peers can ask (the transport
 * delivers nothing else). A peer can only hand over bytes: the receiver installs them through the normal path, so the
 * signature is checked against <em>its</em> pinned publishers and nothing is approved or run.
 *
 * <pre>request:  0x01 | u32 request id | 32-byte payload hash
 * answer:   0x02 | u32 request id | .nrpkg bytes      or     0x03 | u32 request id   (not here)</pre>
 */
public final class PackageExchange implements AutoCloseable {

    private static final byte GET = 1, FOUND = 2, MISSING = 3;

    private final MeshTransport transport;
    private final PackageManager packages;
    private final AtomicInteger ids = new AtomicInteger(1);
    private record Waiting(NodeId peer, CompletableFuture<byte[]> reply) {
    }

    private final Map<Integer, Waiting> pending = new ConcurrentHashMap<>();
    private final BiConsumer<NodeId, byte[]> handler = this::onFrame;

    public PackageExchange(MeshTransport transport, PackageManager packages) {
        this.transport = transport;
        this.packages = packages;
        transport.subscribe(MeshChannel.PACKAGES, handler);
    }

    /** Asks {@code peer} for the package whose payload hash is {@code sha256}; installs nothing. */
    public Optional<PackageArchive> fetch(NodeId peer, String sha256, Duration timeout) throws FsException {
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("not a SHA-256: " + sha256);
        int id = ids.getAndIncrement();
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        pending.put(id, new Waiting(peer, f));
        try {
            transport.send(peer, MeshChannel.PACKAGES, ByteBuffer.allocate(37).put(GET).putInt(id)
                    .put(HexFormat.of().parseHex(sha256)).array());
            byte[] reply = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (reply[0] == MISSING) return Optional.empty();
            PackageArchive a = PackageArchive.read(Arrays.copyOfRange(reply, 5, reply.length));
            if (!PackageManifest.parse(a.manifest()).payloadSha256().equals(sha256)) {
                throw new PackageException(PackageException.Reason.PAYLOAD_MISMATCH, peer + " answered with another package");
            }
            return Optional.of(a);
        } catch (TimeoutException e) {
            throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, peer + " did not answer");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FsException(FsErrorCode.INTERNAL, "interrupted");
        } catch (ExecutionException e) {
            throw new FsException(FsErrorCode.INTERNAL, e.getCause().getMessage());
        } finally {
            pending.remove(id);
        }
    }

    private void onFrame(NodeId from, byte[] frame) {
        if (frame == null || frame.length < 5) return;
        int id = ByteBuffer.wrap(frame, 1, 4).getInt();
        switch (frame[0]) {
            case GET -> {
                if (frame.length != 37) return;
                String sha = HexFormat.of().formatHex(frame, 5, 37);
                Optional<PackageArchive> a;
                try {
                    a = packages.archiveByPayload(sha);
                } catch (RuntimeException e) {
                    a = Optional.empty();
                }
                byte[] body = a.map(PackageArchive::toBytes).orElse(new byte[0]);
                ByteBuffer out = ByteBuffer.allocate(5 + body.length).put(a.isPresent() ? FOUND : MISSING).putInt(id).put(body);
                try {
                    transport.send(from, MeshChannel.PACKAGES, out.array());
                    packages.audit().record("served", sha, "to " + from.hex());
                } catch (FsException ignored) {
                    // the asker went away
                }
            }
            case FOUND, MISSING -> {
                Waiting w = pending.get(id);
                if (w != null && w.peer().equals(from)) w.reply().complete(frame); // only the peer we asked
            }
            default -> { }
        }
    }

    @Override
    public void close() {
        transport.unsubscribe(MeshChannel.PACKAGES, handler);
    }
}
