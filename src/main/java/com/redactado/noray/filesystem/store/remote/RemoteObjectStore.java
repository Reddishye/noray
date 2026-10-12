package com.redactado.noray.filesystem.store.remote;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.ContentDigest;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.ObjectStore;
import com.redactado.noray.filesystem.store.PutResult;
import com.redactado.noray.net.MeshChannel;
import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.net.NodeId;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * {@link ObjectStore} backed by a remote peer over the mesh FS_BLOB channel.
 *
 * <p>A single dispatch handler is registered on the transport (lazily, on first use). Requests are
 * correlated with replies through the {@code requestId} echoed by the peer, and replies from nodes
 * other than the configured peer are ignored. Call {@link #close()} to unsubscribe.
 */
public class RemoteObjectStore implements ObjectStore, AutoCloseable {

    private static final long DEFAULT_TIMEOUT_SECONDS = 30; // chunks are megabytes: sending and answering take a while
    private volatile long timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    private final MeshTransport transport;
    private final NodeId peer;

    private final AtomicInteger nextRequestId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();
    private final BiConsumer<NodeId, byte[]> dispatcher = this::onFrame;

    private boolean subscribed;
    private boolean closed;

    public RemoteObjectStore(MeshTransport transport, NodeId peer) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.peer = Objects.requireNonNull(peer, "peer");
    }

    /** How long to wait for the answer to one request. */
    public RemoteObjectStore timeout(java.time.Duration timeout) {
        this.timeoutSeconds = Math.max(1, timeout.toSeconds());
        return this;
    }

    private synchronized void ensureSubscribed() {
        if (closed) {
            throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "Remote store is closed");
        }
        if (!subscribed) {
            transport.subscribe(MeshChannel.FS_BLOB, dispatcher);
            subscribed = true;
        }
    }

    private void onFrame(NodeId from, byte[] frame) {
        if (!peer.equals(from) || frame == null || frame.length < BlobProtocol.HEADER_SIZE) {
            return;
        }
        byte op = BlobProtocol.decodeOp(frame);
        if (op != BlobProtocol.HAS_REPLY && op != BlobProtocol.GET_REPLY
                && op != BlobProtocol.PUT_REPLY && op != BlobProtocol.ERR && op != BlobProtocol.DELETE_REPLY
                && op != BlobProtocol.LIST_REPLY && op != BlobProtocol.SIZE_REPLY) {
            return; // a request or unknown frame, not for us
        }
        goneUntilNanos = System.nanoTime() - 1; // it answered: whatever we concluded earlier no longer holds
        CompletableFuture<byte[]> future = pending.remove(BlobProtocol.decodeRequestId(frame));
        if (future != null) {
            future.complete(frame);
        }
    }

    /**
     * Sends a request built from the given requestId and waits for its correlated reply.
     * The pending entry is always removed before returning.
     */
    private byte[] request(java.util.function.IntFunction<byte[]> encoder, byte expectedReplyOp, String what) {
        return request(encoder, expectedReplyOp, what, TimeUnit.SECONDS.toMillis(timeoutSeconds));
    }

    private byte[] request(java.util.function.IntFunction<byte[]> encoder, byte expectedReplyOp, String what, long timeoutMillis) {
        return request(encoder, expectedReplyOp, what, timeoutMillis, true);
    }

    private byte[] request(java.util.function.IntFunction<byte[]> encoder, byte expectedReplyOp, String what, long timeoutMillis,
                           boolean watch) {
        ensureSubscribed();
        if (watch && System.nanoTime() - goneUntilNanos < 0) {
            com.redactado.noray.kernel.metrics.Metrics.global().inc("remote.fastfail");
            throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "peer recently stopped answering: " + peer);
        }
        int requestId = nextRequestId.getAndIncrement();
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        pending.put(requestId, future);
        try {
            transport.send(peer, MeshChannel.FS_BLOB, encoder.apply(requestId));
            byte[] reply = awaitReply(future, timeoutMillis, watch);
            byte op = BlobProtocol.decodeOp(reply);
            if (op == BlobProtocol.ERR) {
                throw new FsException(remoteCode(reply), "Remote " + what + " refused or failed");
            }
            if (op != expectedReplyOp) {
                throw new FsException(FsErrorCode.INTERNAL, "Remote " + what + " unexpected reply: " + op);
            }
            return reply;
        } catch (TimeoutException e) {
            throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "Remote " + what + " timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FsException(FsErrorCode.INTERNAL, "Remote " + what + " interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof FsException fe) {
                throw fe;
            }
            throw new FsException(FsErrorCode.INTERNAL, "Remote " + what + " failed", e);
        } finally {
            pending.remove(requestId);
        }
    }

    /**
     * Waits for the reply. A reply that is slow is not a reason to give up, a peer that is gone is: after
     * {@link #silenceBeforeProbe} without a reply the peer is asked a trivial question, and if it does not answer that
     * either the request fails at once instead of waiting out the whole timeout.
     */
    private byte[] awaitReply(CompletableFuture<byte[]> future, long timeoutMillis, boolean watch)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (left <= 0) throw new TimeoutException();
            long slice = watch ? Math.min(left, silenceBeforeProbe) : left;
            try {
                return future.get(slice, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (!watch || slice >= left) throw e;
                com.redactado.noray.kernel.metrics.Metrics.global().inc("remote.probes");
                if (!probe(1500)) {
                    goneUntilNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(goneHoldMillis);
                    com.redactado.noray.kernel.metrics.Metrics.global().inc("remote.peers.gone");
                    throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "peer stopped answering: " + peer);
                }
            }
        }
    }

    private volatile long silenceBeforeProbe = 5_000;

    /*
     * Circuit breaker: once a probe has found the peer gone, further requests fail at once for goneHoldMillis instead of each
     * waiting silenceBeforeProbe plus a probe. Probes (health checks) bypass it, and any frame from the peer clears it.
     */
    private volatile long goneHoldMillis = 10_000;
    private volatile long goneUntilNanos = System.nanoTime() - 1;

    /** How long requests fail fast after the peer was found gone (default 10 s; zero disables). */
    public RemoteObjectStore goneHold(java.time.Duration d) {
        this.goneHoldMillis = Math.max(0, d.toMillis());
        return this;
    }

    /** How long a request may stay unanswered before the peer is probed (default 5 s). */
    public RemoteObjectStore silenceBeforeProbe(java.time.Duration d) {
        this.silenceBeforeProbe = Math.max(100, d.toMillis());
        return this;
    }

    /**
     * True if the peer answers at all within about {@code millis} (a refusal counts: someone is there). Asks twice
     * so that one lost datagram does not make a healthy node look dead.
     */
    public boolean alive(long millis) {
        boolean alive = probe(millis);
        if (alive) goneUntilNanos = System.nanoTime() - 1;
        return alive;
    }

    private boolean probe(long millis) {
        ObjectId probe = ContentDigest.sha256(new byte[0]);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                request(rid -> BlobProtocol.encodeHas(rid, probe), BlobProtocol.HAS_REPLY, "probe", Math.max(200, millis / 2), false);
                return true;
            } catch (FsException e) {
                if (e.getMessage() != null && e.getMessage().contains("refused or failed")) {
                    return true; // an error reply is still an answer
                }
                if (e.getMessage() != null && e.getMessage().contains("handshake")) {
                    return false; // not even a session: asking again only repeats the wait
                }
            }
        }
        return false;
    }

    @Override
    public PutResult put(byte[] data) throws FsException {
        Objects.requireNonNull(data, "data");
        byte[] reply = request(id -> BlobProtocol.encodePut(id, data), BlobProtocol.PUT_REPLY, "put");
        try {
            ObjectId id = BlobProtocol.decodeDigest(reply);
            return new PutResult(id, data.length, true);
        } catch (IllegalArgumentException e) {
            throw new FsException(FsErrorCode.INTERNAL, "Malformed remote put reply", e);
        }
    }

    @Override
    public byte[] get(ObjectId id) throws FsException {
        Objects.requireNonNull(id, "id");
        byte[] reply = request(rid -> BlobProtocol.encodeGet(rid, id), BlobProtocol.GET_REPLY, "get");
        try {
            return BlobProtocol.decodeData(reply);
        } catch (IllegalArgumentException e) {
            throw new FsException(FsErrorCode.INTERNAL, "Malformed remote get reply", e);
        }
    }

    @Override
    public boolean has(ObjectId id) {
        Objects.requireNonNull(id, "id");
        try {
            byte[] reply = request(rid -> BlobProtocol.encodeHas(rid, id), BlobProtocol.HAS_REPLY, "has");
            return BlobProtocol.decodeBoolean(reply);
        } catch (FsException e) {
            if (e.code() == FsErrorCode.VOLUME_UNAVAILABLE || e.code() == FsErrorCode.DENIED) {
                throw e; // a refusal must never read as "the blob is absent"
            }
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static FsErrorCode remoteCode(byte[] errFrame) {
        try {
            int ordinal = BlobProtocol.decodeErrorCode(errFrame);
            FsErrorCode[] all = FsErrorCode.values();
            return ordinal >= 0 && ordinal < all.length ? all[ordinal] : FsErrorCode.INTERNAL;
        } catch (IllegalArgumentException e) {
            return FsErrorCode.INTERNAL;
        }
    }

    /** Needs the DELETE operation in this node's certificate on the peer; {@code DENIED} otherwise. */
    @Override
    public void delete(ObjectId id) throws FsException {
        Objects.requireNonNull(id, "id");
        request(rid -> BlobProtocol.encodeDelete(rid, id), BlobProtocol.DELETE_REPLY, "delete");
    }

    @Override
    public long size(ObjectId id) throws FsException {
        Objects.requireNonNull(id, "id");
        byte[] reply = request(rid -> BlobProtocol.encodeSize(rid, id), BlobProtocol.SIZE_REPLY, "size");
        try {
            return BlobProtocol.decodeSize(reply);
        } catch (IllegalArgumentException e) {
            throw new FsException(FsErrorCode.INTERNAL, "Malformed remote size reply", e);
        }
    }

    /** Needs the LIST operation in this node's certificate on the peer; {@code DENIED} otherwise. */
    @Override
    public Set<ObjectId> listIds() {
        Set<ObjectId> all = new java.util.HashSet<>();
        ObjectId cursor = null;
        while (true) {
            ObjectId after = cursor;
            byte[] reply = request(rid -> BlobProtocol.encodeList(rid, after), BlobProtocol.LIST_REPLY, "list");
            BlobProtocol.ListPage page;
            try {
                page = BlobProtocol.decodeListReply(reply);
            } catch (IllegalArgumentException e) {
                throw new FsException(FsErrorCode.INTERNAL, "Malformed remote list reply", e);
            }
            all.addAll(page.ids());
            if (!page.more() || page.ids().isEmpty()) {
                return all;
            }
            cursor = page.ids().get(page.ids().size() - 1);
        }
    }

    /** Unsubscribes from the transport and fails any in-flight requests. Idempotent. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (subscribed) {
            transport.unsubscribe(MeshChannel.FS_BLOB, dispatcher);
            subscribed = false;
        }
        FsException closedEx = new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "Remote store closed");
        pending.values().forEach(f -> f.completeExceptionally(closedEx));
        pending.clear();
    }
}
