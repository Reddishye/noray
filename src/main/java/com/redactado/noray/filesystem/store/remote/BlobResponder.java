package com.redactado.noray.filesystem.store.remote;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.ContentDigest;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.ObjectStore;
import com.redactado.noray.identity.AccessPolicy;
import com.redactado.noray.identity.Op;
import com.redactado.noray.net.MeshChannel;
import com.redactado.noray.net.MeshTransport;
import com.redactado.noray.net.NodeId;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Serves this node's blob store to its peers. Every request is checked against the {@link AccessPolicy} before
 * anything is touched: with certificates, a peer that may store and read still cannot delete or enumerate.
 */
public final class BlobResponder implements AutoCloseable {

    private final MeshTransport transport;
    private final ObjectStore store;
    private final AccessPolicy policy;
    private final long maxPutBytes;
    private final DeleteQuarantine quarantine; // null: remote deletes are immediate
    private volatile PeerRateLimiter putLimiter;
    private volatile PeerQuota quota;
    private final BiConsumer<NodeId, byte[]> handler;
    private final AtomicLong denied = new AtomicLong();
    private boolean started;
    private java.util.concurrent.ScheduledExecutorService sweeper;

    /** Unmanaged: any enrolled peer may do anything. For tests and setups without an authority. */
    public BlobResponder(MeshTransport transport, ObjectStore store) {
        this(transport, store, AccessPolicy.allowAll(), 0);
    }

    /** @param maxPutBytes largest blob a peer may store, 0 for no limit */
    public BlobResponder(MeshTransport transport, ObjectStore store, AccessPolicy policy, long maxPutBytes) {
        this(transport, store, policy, maxPutBytes, null);
    }

    /** @param quarantine when set, remote deletes are delayed and can be undone (see {@link DeleteQuarantine}) */
    public BlobResponder(MeshTransport transport, ObjectStore store, AccessPolicy policy, long maxPutBytes,
                         DeleteQuarantine quarantine) {
        this.quarantine = quarantine;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.store = Objects.requireNonNull(store, "store");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.maxPutBytes = maxPutBytes;
        this.handler = this::onFrame;
    }

    /** Bounds how much each peer may store per minute (optional). */
    public void limitPuts(PeerRateLimiter limiter) {
        this.putLimiter = limiter;
    }

    /** Bounds how much each peer may occupy in total (optional). */
    public void quota(PeerQuota quota) {
        this.quota = quota;
        if (quarantine != null) {
            quarantine.onRemoved(quota::release);
        }
    }

    public java.util.Optional<PeerQuota> quota() {
        return java.util.Optional.ofNullable(quota);
    }

    public java.util.Optional<DeleteQuarantine> quarantine() {
        return java.util.Optional.ofNullable(quarantine);
    }

    private boolean hidden(ObjectId id) {
        return quarantine != null && quarantine.isHidden(id);
    }

    /** Requests refused so far (policy or size limit). */
    public long denied() {
        return denied.get();
    }

    private static Op required(byte op) {
        return switch (op) {
            case BlobProtocol.HAS, BlobProtocol.SIZE -> Op.HAS;
            case BlobProtocol.GET -> Op.GET;
            case BlobProtocol.PUT -> Op.PUT;
            case BlobProtocol.DELETE -> Op.DELETE;
            case BlobProtocol.LIST -> Op.LIST;
            default -> null; // replies and unknown frames are not ours
        };
    }

    private void requireVisible(ObjectId id) {
        if (hidden(id)) {
            throw new FsException(FsErrorCode.NOT_FOUND, "no such blob");
        }
    }

    private void onFrame(NodeId from, byte[] payload) {
        if (payload == null || payload.length < BlobProtocol.HEADER_SIZE) {
            return;
        }
        byte op = BlobProtocol.decodeOp(payload);
        Op needed = required(op);
        if (needed == null) {
            return;
        }
        int requestId = BlobProtocol.decodeRequestId(payload);
        var metrics = com.redactado.noray.kernel.metrics.Metrics.global();
        metrics.inc("blob.served." + needed.name().toLowerCase());
        try {
            policy.check(from, needed);
            switch (op) {
                case BlobProtocol.HAS -> {
                    ObjectId id = BlobProtocol.decodeDigest(payload);
                    transport.send(from, MeshChannel.FS_BLOB,
                            BlobProtocol.encodeHasReply(requestId, !hidden(id) && store.has(id)));
                }
                case BlobProtocol.SIZE -> {
                    ObjectId id = BlobProtocol.decodeDigest(payload);
                    requireVisible(id);
                    transport.send(from, MeshChannel.FS_BLOB, BlobProtocol.encodeSizeReply(requestId, store.size(id)));
                }
                case BlobProtocol.GET -> {
                    ObjectId id = BlobProtocol.decodeDigest(payload);
                    requireVisible(id);
                    byte[] data = store.get(id);
                    metrics.add("blob.bytes.out", data.length);
                    transport.send(from, MeshChannel.FS_BLOB, BlobProtocol.encodeGetReply(requestId, data));
                }
                case BlobProtocol.PUT -> {
                    byte[] data = BlobProtocol.decodePutData(payload);
                    if (maxPutBytes > 0 && data.length > maxPutBytes) {
                        throw new FsException(FsErrorCode.DENIED, "blob larger than " + maxPutBytes + " bytes");
                    }
                    PeerRateLimiter limiter = putLimiter;
                    if (limiter != null) {
                        limiter.charge(from, data.length);
                    }
                    ObjectId id = ContentDigest.sha256(data);
                    PeerQuota q = quota;
                    boolean charged = false;
                    if (q != null && !store.has(id)) {
                        q.reserve(from, id, data.length);
                        charged = true;
                    }
                    try {
                        store.put(data);
                        metrics.add("blob.bytes.in", data.length);
                    } catch (RuntimeException e) {
                        if (charged) q.release(id);
                        throw e;
                    }
                    if (quarantine != null) {
                        quarantine.revive(id);
                    }
                    transport.send(from, MeshChannel.FS_BLOB, BlobProtocol.encodePutReply(requestId, id));
                }
                case BlobProtocol.DELETE -> {
                    ObjectId victim = BlobProtocol.decodeDigest(payload);
                    if (quarantine != null && quarantine.delay().toMillis() > 0) {
                        quarantine.sweep();
                        quarantine.quarantine(victim);
                    } else {
                        store.delete(victim);
                        if (quota != null) quota.release(victim);
                    }
                    transport.send(from, MeshChannel.FS_BLOB, BlobProtocol.encodeDeleteReply(requestId));
                }
                case BlobProtocol.LIST -> {
                    ObjectId after = BlobProtocol.decodeListCursor(payload);
                    List<ObjectId> sorted = store.listIds().stream()
                            .filter(id -> !hidden(id))
                            .filter(id -> after == null || id.hex().compareTo(after.hex()) > 0)
                            .sorted(Comparator.comparing(ObjectId::hex))
                            .limit((long) BlobProtocol.LIST_PAGE + 1)
                            .toList();
                    boolean more = sorted.size() > BlobProtocol.LIST_PAGE;
                    transport.send(from, MeshChannel.FS_BLOB, BlobProtocol.encodeListReply(requestId,
                            more ? sorted.subList(0, BlobProtocol.LIST_PAGE) : sorted, more));
                }
                default -> {
                }
            }
        } catch (FsException e) {
            if (e.code() == FsErrorCode.DENIED) {
                denied.incrementAndGet();
                metrics.inc("blob.denied");
            }
            try {
                transport.send(from, MeshChannel.FS_BLOB, BlobProtocol.encodeError(requestId, e.code().ordinal()));
            } catch (FsException ignored) {
                // the peer is gone or not reachable: nothing to tell
            }
        } catch (IllegalArgumentException e) {
            // malformed frame: drop it
        }
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        transport.subscribe(MeshChannel.FS_BLOB, handler);
        started = true;
        if (quarantine != null && quarantine.delay().toMillis() > 0 || quota != null) {
            sweeper = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "noray-delete-sweeper");
                t.setDaemon(true);
                return t;
            });
            int[] round = {0};
            sweeper.scheduleWithFixedDelay(() -> {
                try {
                    if (quarantine != null) quarantine.sweep();
                    PeerQuota q = quota;
                    if (q != null) {
                        if (round[0]++ % 10 == 9) {
                            q.reconcile(store.listIds());
                        }
                        q.flush();
                    }
                } catch (RuntimeException ignored) {
                    // next round
                }
            }, 1, 1, java.util.concurrent.TimeUnit.MINUTES);
        }
    }

    @Override
    public synchronized void close() {
        if (!started) {
            return;
        }
        transport.unsubscribe(MeshChannel.FS_BLOB, handler);
        started = false;
        if (quota != null) {
            quota.flush();
        }
        if (sweeper != null) {
            sweeper.shutdownNow();
            sweeper = null;
        }
    }
}
