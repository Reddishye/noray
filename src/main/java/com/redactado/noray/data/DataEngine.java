package com.redactado.noray.data;

import com.redactado.noray.data.codec.Codecs;
import com.redactado.noray.data.crypto.DataCrypto;
import com.redactado.noray.data.manifest.FileRef;
import com.redactado.noray.data.manifest.Manifest.BlobLoc;
import com.redactado.noray.data.manifest.Manifest.Chunk;
import com.redactado.noray.data.manifest.Manifest.Page;
import com.redactado.noray.data.manifest.Manifest.PageRef;
import com.redactado.noray.data.manifest.Manifest.Variant;
import com.redactado.noray.data.manifest.ManifestCodec;
import com.redactado.noray.data.placement.DataCluster;
import com.redactado.noray.data.placement.Placement;
import com.redactado.noray.data.placement.StorageNode;
import com.redactado.noray.data.placement.Tier;
import com.redactado.noray.data.redundancy.ReedSolomon;
import com.redactado.noray.data.redundancy.Redundancy;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.ContentDigest;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.store.PutResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Stores files as encrypted, compressed, redundant chunks spread over the nodes of a {@link DataCluster}.
 *
 * <p><b>Temperature belongs to the data, not to the nodes.</b> Every node may hold every {@link Tier}; which
 * variants a chunk gets is decided by the {@link StoragePolicy} used when it is written, and can differ per
 * file, per folder (the caller picks the policy) or per range of chunks ({@link #retier}). Hot data is spread
 * over all nodes weighted by speed, cold data weighted by capacity, so every node contributes bandwidth.
 *
 * <p>Write path, per chunk and per policy variant:
 * <pre>
 *   plaintext -&gt; compress (variant codec) -&gt; pad -&gt; encrypt (AEAD, one-time key)
 *             -&gt; replicate or erasure code -&gt; place by weighted rendezvous hashing
 * </pre>
 * Storage nodes only ever see ciphertext. The chunk list lives in an encrypted Merkle tree of manifest pages
 * whose root, together with the file key, is the {@link FileRef} returned to the caller. Keeping that reference
 * (and its key) safe is the caller's job; the engine never stores it.
 *
 * <p>Chunks are written and read in parallel (a window of {@code window} chunks in flight), replicas are chosen
 * at random so load spreads over all copies, and erasure-coded chunks fetch their shards concurrently. Every
 * blob is verified against its content hash and authenticated before use.
 */
public final class DataEngine implements AutoCloseable {

    /** Maximum number of items per manifest page. */
    public static final int DEFAULT_FANOUT = 512;
    /** Chunks in flight at once while reading or writing a file. */
    public static final int DEFAULT_WINDOW = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A range of chunks {@code [fromChunk, toChunk)} that should follow a different policy. */
    public record Segment(long fromChunk, long toChunk, StoragePolicy policy) {
        public Segment {
            Objects.requireNonNull(policy, "policy");
            if (fromChunk < 0 || toChunk < fromChunk) {
                throw new IllegalArgumentException("invalid chunk range");
            }
        }

        boolean covers(long index) {
            return index >= fromChunk && index < toChunk;
        }
    }

    private final DataCluster cluster;
    private final StoragePolicy defaultPolicy;
    private final int fanout;
    private final int window;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** Nodes found to serve corrupt data during a running repair: no new data goes there until it ends. */
    private final Set<String> suspects = ConcurrentHashMap.newKeySet();
    private final Map<Long, ReedSolomon> rsCache = new ConcurrentHashMap<>();
    /** Blobs this engine wrote recently (id to millis): garbage collection leaves them alone. */
    private final Map<ObjectId, Long> recentWrites = new ConcurrentHashMap<>();

    public DataEngine(DataCluster cluster, StoragePolicy defaultPolicy) {
        this(cluster, defaultPolicy, DEFAULT_FANOUT, DEFAULT_WINDOW);
    }

    public DataEngine(DataCluster cluster, StoragePolicy defaultPolicy, int fanout) {
        this(cluster, defaultPolicy, fanout, DEFAULT_WINDOW);
    }

    /**
     * @param fanout items per manifest page; small values are only useful to exercise multi-level trees in tests
     * @param window chunks processed concurrently
     */
    public DataEngine(DataCluster cluster, StoragePolicy defaultPolicy, int fanout, int window) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.defaultPolicy = Objects.requireNonNull(defaultPolicy, "defaultPolicy");
        if (fanout < 2 || fanout > 65536) {
            throw new IllegalArgumentException("fanout must be 2..65536");
        }
        if (window < 1) {
            throw new IllegalArgumentException("window must be >= 1");
        }
        this.fanout = fanout;
        this.window = window;
    }

    public StoragePolicy defaultPolicy() {
        return defaultPolicy;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Write
    // ════════════════════════════════════════════════════════════════════════════

    public FileRef put(byte[] data) throws FsException {
        return put(new ByteArrayInputStream(Objects.requireNonNull(data, "data")), defaultPolicy);
    }

    public FileRef put(InputStream in) throws FsException {
        return put(in, defaultPolicy);
    }

    public FileRef put(byte[] data, StoragePolicy policy) throws FsException {
        return put(new ByteArrayInputStream(Objects.requireNonNull(data, "data")), policy);
    }

    /**
     * Streams {@code in} into the cluster following {@code policy}; memory use is bounded by
     * {@code window} chunks (plus their variants).
     */
    public FileRef put(InputStream in, StoragePolicy policy) throws FsException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(policy, "policy");
        byte[] fileKey = DataCrypto.randomKey();
        BlobLog written = new BlobLog();
        TreeBuilder tree = new TreeBuilder(fileKey, RANDOM.nextLong(), policy.chunkSize(), fanout,
                pageTiers(List.of(policy)), policy.metadataReplicas(), written);
        Window<Chunk> pipeline = new Window<>(tree::add);
        try {
            byte[] buf = new byte[policy.chunkSize()];
            long total = 0;
            int index = 0;
            while (true) {
                int n = in.readNBytes(buf, 0, buf.length);
                if (n == 0) {
                    break;
                }
                byte[] plain = n == buf.length ? buf.clone() : Arrays.copyOf(buf, n);
                int chunkIndex = index++;
                pipeline.submit(() -> storeChunk(fileKey, chunkIndex, plain, policy, written));
                total += n;
                if (n < buf.length) {
                    break;
                }
            }
            pipeline.drain();
            return tree.finish(total);
        } catch (IOException e) {
            pipeline.abort();
            discard(written);
            throw new FsException(FsErrorCode.INTERNAL, "failed to read input", e);
        } catch (RuntimeException e) {
            pipeline.abort();
            discard(written);
            throw e;
        }
    }

    private Chunk storeChunk(byte[] fileKey, int index, byte[] plain, StoragePolicy policy, Collection<BlobLoc> written) {
        List<Variant> stored = new ArrayList<>();
        Set<String> usedNodes = new HashSet<>(); // later variants avoid nodes that already hold this chunk
        for (StoragePolicy.Variant vp : policy.variants()) {
            stored.add(createVariant(fileKey, index, plain, vp, policy, usedNodes, written));
        }
        return new Chunk(plain.length, stored);
    }

    private Variant createVariant(byte[] fileKey, int index, byte[] plain, StoragePolicy.Variant vp,
                                  StoragePolicy policy, Set<String> usedNodes, Collection<BlobLoc> written) {
        int codecId = vp.codecId();
        byte[] compressed = Codecs.byId(codecId).compress(plain);
        if (codecId != Codecs.NONE_ID && compressed.length >= plain.length - plain.length / 20) {
            // less than 5% gained: not worth the CPU on every read
            codecId = Codecs.NONE_ID;
            compressed = plain;
        }

        long salt = RANDOM.nextLong();
        byte[] frame = frame(compressed, policy.padding());
        byte[] key = DataCrypto.derive(fileKey, chunkContext(index, salt));
        byte[] cipher = DataCrypto.seal(key, chunkAad(index, salt, vp.tier(), codecId, plain.length), frame);

        Redundancy red = effectiveRedundancy(vp.redundancy(), cipher.length, policy);
        List<byte[]> pieces = new ArrayList<>();
        int shardLen = 0;
        if (red instanceof Redundancy.Replicas r) {
            for (int i = 0; i < r.n(); i++) {
                pieces.add(cipher);
            }
        } else if (red instanceof Redundancy.ErasureCoded ec) {
            shardLen = (cipher.length + ec.k() - 1) / ec.k();
            byte[][] data = new byte[ec.k()][shardLen];
            for (int i = 0; i < ec.k(); i++) {
                int from = i * shardLen;
                if (from < cipher.length) {
                    System.arraycopy(cipher, from, data[i], 0, Math.min(shardLen, cipher.length - from));
                }
            }
            byte[][] parity = reedSolomon(ec.k(), ec.m()).encode(data);
            pieces.addAll(Arrays.asList(data));
            pieces.addAll(Arrays.asList(parity));
        }

        List<StorageNode> pool = nodesOf(vp.tier());
        if (pool.size() < red.totalBlobs()) {
            throw new FsException(FsErrorCode.QUORUM_FAILED, vp.tier() + " data in " + red + " needs "
                    + red.totalBlobs() + " available nodes accepting it, only " + pool.size() + " found");
        }
        List<BlobLoc> locs = storePieces(pieces, pool, ContentDigest.sha256(cipher).hex(), vp.tier(), usedNodes, written);
        return new Variant(vp.tier(), codecId, red, cipher.length, shardLen, salt, locs);
    }

    /** Small chunks are never erasure coded: {@code m+1} full copies tolerate the same number of losses. */
    private static Redundancy effectiveRedundancy(Redundancy requested, int cipherLen, StoragePolicy policy) {
        if (requested instanceof Redundancy.ErasureCoded ec && cipherLen < policy.ecMinCipherLen()) {
            return Redundancy.replicas(Math.min(255, ec.m() + 1));
        }
        return requested;
    }

    /**
     * Writes each piece to a different node, walking the placement order (weights of {@code tier}) and skipping
     * nodes that fail. Nodes in {@code avoid} (already holding another piece of the same chunk) go last.
     */
    private List<BlobLoc> storePieces(List<byte[]> pieces, List<StorageNode> pool, String placementKey, Tier tier,
                                      Set<String> avoid, Collection<BlobLoc> written) {
        List<StorageNode> ordered = Placement.candidates(pool, placementKey, n -> n.weightFor(tier));
        if (!avoid.isEmpty()) {
            List<StorageNode> fresh = new ArrayList<>();
            List<StorageNode> reused = new ArrayList<>();
            for (StorageNode n : ordered) {
                (avoid.contains(n.id()) ? reused : fresh).add(n);
            }
            fresh.addAll(reused);
            ordered = fresh;
        }
        Iterator<StorageNode> candidates = ordered.iterator();
        List<BlobLoc> locs = new ArrayList<>(pieces.size());
        RuntimeException lastError = null;
        for (int i = 0; i < pieces.size(); i++) {
            byte[] piece = pieces.get(i);
            ObjectId expected = ContentDigest.sha256(piece);
            boolean stored = false;
            while (!stored && candidates.hasNext()) {
                StorageNode node = candidates.next();
                try {
                    PutResult pr = node.store().put(piece);
                    if (!pr.id().equals(expected)) {
                        throw new FsException(FsErrorCode.DIGEST_MISMATCH, "node " + node.id() + " stored a different blob");
                    }
                    BlobLoc loc = new BlobLoc(node.id(), expected, i);
                    recentWrites.put(expected, System.currentTimeMillis());
                    if (recentWrites.size() > 500_000) {
                        long old = System.currentTimeMillis() - 6L * 3600 * 1000;
                        recentWrites.values().removeIf(t -> t < old);
                    }
                    locs.add(loc);
                    written.add(loc);
                    avoid.add(node.id());
                    stored = true;
                } catch (RuntimeException e) {
                    lastError = e;
                }
            }
            if (!stored) {
                throw new FsException(FsErrorCode.QUORUM_FAILED, "not enough healthy nodes to store "
                        + pieces.size() + " pieces (stored " + i + ")", lastError);
            }
        }
        return locs;
    }

    /** Best-effort removal of blobs written by an operation that failed half way. */
    private void discard(Collection<BlobLoc> blobs) {
        for (BlobLoc loc : blobs) {
            try {
                // a node that is down would only make us wait: its copy is an orphan that garbage collection takes later
                cluster.find(loc.nodeId()).filter(StorageNode::available).ifPresent(n -> n.store().delete(loc.blobId()));
            } catch (RuntimeException ignored) {
                // unreachable or unsupported: the blob stays orphaned until garbage collection exists
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Re-tiering
    // ════════════════════════════════════════════════════════════════════════════

    public FileRef retier(FileRef ref, StoragePolicy policy) throws FsException {
        return retier(ref, policy, List.of());
    }

    /**
     * Changes how a file is stored without changing its content: chunks in a {@link Segment} follow that
     * segment's policy, all others follow {@code base}. Variants that already satisfy the target are kept as
     * they are (no data moves); missing variants are created from the decrypted chunk; variants no longer wanted
     * are deleted once the new manifest is safely stored.
     *
     * <p>Returns a <b>new</b> {@link FileRef} (same data key, new manifest). The old reference keeps working for
     * the variants that survive, but should be dropped. The old manifest pages are left behind as orphans.
     * Chunk size and fanout are those of the existing tree.
     */
    public FileRef retier(FileRef ref, StoragePolicy base, List<Segment> segments) throws FsException {
        Objects.requireNonNull(base, "base");
        List<Segment> overrides = List.copyOf(segments);
        Page root = loadRoot(ref);
        byte[] key = ref.key();

        List<StoragePolicy> all = new ArrayList<>();
        all.add(base);
        overrides.forEach(s -> all.add(s.policy()));
        BlobLog written = new BlobLog();
        BlobLog obsolete = new BlobLog();
        TreeBuilder tree = new TreeBuilder(key, RANDOM.nextLong(), root.chunkSize(), root.fanout(), pageTiers(all),
                all.stream().mapToInt(StoragePolicy::metadataReplicas).max().orElse(1), written);
        Window<Chunk> pipeline = new Window<>(tree::add);
        try {
            long[] counter = {0};
            walk(root, 0, key, ref.manifestSalt(), chunk -> {
                int index = (int) counter[0]++;
                StoragePolicy p = base;
                for (Segment s : overrides) {
                    if (s.covers(index)) {
                        p = s.policy();
                        break;
                    }
                }
                StoragePolicy chosen = p;
                pipeline.submit(() -> retierChunk(key, index, chunk, chosen, written, obsolete));
            });
            pipeline.drain();
            FileRef result = tree.finish(root.totalSize());
            discard(obsolete);
            return result;
        } catch (RuntimeException e) {
            pipeline.abort();
            discard(written);
            throw e;
        }
    }

    private Chunk retierChunk(byte[] key, int index, Chunk chunk, StoragePolicy policy, Collection<BlobLoc> written,
                              Collection<BlobLoc> obsolete) {
        List<Variant> existing = chunk.variants();
        boolean[] kept = new boolean[existing.size()];
        Variant[] result = new Variant[policy.variants().size()];
        Set<String> usedNodes = new HashSet<>();

        // pass 1: reuse what already satisfies the target
        for (int d = 0; d < result.length; d++) {
            StoragePolicy.Variant want = policy.variants().get(d);
            for (int e = 0; e < existing.size(); e++) {
                Variant have = existing.get(e);
                if (!kept[e] && have.tier() == want.tier()
                        && (have.codecId() == want.codecId() || have.codecId() == Codecs.NONE_ID)
                        && have.redundancy().equals(effectiveRedundancy(want.redundancy(), have.cipherLen(), policy))) {
                    kept[e] = true;
                    result[d] = have;
                    have.blobs().forEach(b -> usedNodes.add(b.nodeId()));
                    break;
                }
            }
        }
        // pass 2: create the missing ones
        byte[] plain = null;
        for (int d = 0; d < result.length; d++) {
            if (result[d] == null) {
                if (plain == null) {
                    plain = readChunk(key, index, chunk);
                }
                result[d] = createVariant(key, index, plain, policy.variants().get(d), policy, usedNodes, written);
            }
        }
        for (int e = 0; e < existing.size(); e++) {
            if (!kept[e]) {
                obsolete.addAll(existing.get(e).blobs());
            }
        }
        return new Chunk(chunk.plainLen(), Arrays.asList(result));
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Manifest tree
    // ════════════════════════════════════════════════════════════════════════════

    private static List<Tier> pageTiers(List<StoragePolicy> policies) {
        Set<Tier> tiers = new TreeSet<>(Comparator.comparingInt(Tier::ordinal));
        for (StoragePolicy p : policies) {
            p.variants().forEach(v -> tiers.add(v.tier()));
        }
        return List.copyOf(tiers);
    }

    /** Builds the manifest tree bottom-up while chunks stream in, so memory stays flat for huge files. */
    private final class TreeBuilder {
        private final byte[] fileKey;
        private final long salt;
        private final int chunkSize;
        private final int fanout;
        private final List<Tier> tiers;
        private final int copies;
        private final Collection<BlobLoc> written;
        private final List<Chunk> leaf = new ArrayList<>();
        private final List<List<PageRef>> pending = new ArrayList<>();
        private final List<Integer> emitted = new ArrayList<>();

        TreeBuilder(byte[] fileKey, long salt, int chunkSize, int fanout, List<Tier> tiers, int copies,
                    Collection<BlobLoc> written) {
            this.fileKey = fileKey;
            this.salt = salt;
            this.chunkSize = chunkSize;
            this.fanout = fanout;
            this.tiers = tiers;
            this.copies = copies;
            this.written = written;
            pending.add(new ArrayList<>());
            emitted.add(0);
        }

        void add(Chunk chunk) {
            if (leaf.size() == fanout) { // flush lazily so an exactly full leaf can still become the root
                PageRef ref = emitLeaf();
                leaf.clear();
                push(1, ref);
            }
            leaf.add(chunk);
        }

        private void push(int level, PageRef ref) {
            while (pending.size() <= level) {
                pending.add(new ArrayList<>());
                emitted.add(0);
            }
            List<PageRef> items = pending.get(level);
            if (items.size() == fanout) {
                PageRef up = emitIndex(level, items);
                items.clear();
                push(level + 1, up);
            }
            items.add(ref);
        }

        private PageRef emitLeaf() {
            int idx = emitted.get(0);
            emitted.set(0, idx + 1);
            Page page = new Page(0, chunkSize, fanout, 0, leaf, List.of());
            return storePage(page, pageContext(salt, 0, idx), pageAad(0, idx), tiers, copies, fileKey, written);
        }

        private PageRef emitIndex(int level, List<PageRef> children) {
            int idx = emitted.get(level);
            emitted.set(level, idx + 1);
            Page page = new Page(level, chunkSize, fanout, 0, List.of(), children);
            return storePage(page, pageContext(salt, level, idx), pageAad(level, idx), tiers, copies, fileKey, written);
        }

        FileRef finish(long total) {
            // flush from the bottom: the first level where nothing was emitted holds the root
            int level = 0;
            while (true) {
                if (emitted.get(level) == 0) {
                    Page root = level == 0
                            ? new Page(0, chunkSize, fanout, total, leaf, List.of())
                            : new Page(level, chunkSize, fanout, total, List.of(), pending.get(level));
                    PageRef ref = storePage(root, rootContext(salt), ROOT_AAD, tiers, copies, fileKey, written);
                    return new FileRef(ref.id(), ref.locs(), fileKey, salt);
                }
                if (level == 0) {
                    if (!leaf.isEmpty()) {
                        push(1, emitLeaf());
                        leaf.clear();
                    }
                } else {
                    List<PageRef> items = pending.get(level);
                    if (!items.isEmpty()) {
                        PageRef up = emitIndex(level, items);
                        items.clear();
                        push(level + 1, up);
                    }
                }
                level++;
            }
        }
    }

    private static final String ROOT_AAD = "root";

    /**
     * Manifests are tiny, so every class of data the file uses also gets copies of the pages: losing the nodes
     * that hold the hot copies must not strand the cold ones. Page copies prefer fast nodes.
     */
    private PageRef storePage(Page page, String context, String aad, List<Tier> tiers, int copies, byte[] fileKey,
                              Collection<BlobLoc> written) {
        byte[] sealed = DataCrypto.seal(DataCrypto.derive(fileKey, context), aad.getBytes(StandardCharsets.UTF_8),
                ManifestCodec.encode(page));
        ObjectId id = ContentDigest.sha256(sealed);
        List<BlobLoc> locs = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Tier tier : tiers) {
            List<StorageNode> pool = nodesOf(tier);
            int n = Math.min(copies, pool.size());
            if (n < 1) {
                throw new FsException(FsErrorCode.QUORUM_FAILED, "no available node accepting " + tier + " data");
            }
            List<byte[]> pieces = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                pieces.add(sealed);
            }
            locs.addAll(storePieces(pieces, pool, id.hex(), Tier.HOT, used, written));
        }
        return new PageRef(id, locs);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Read
    // ════════════════════════════════════════════════════════════════════════════

    public long size(FileRef ref) throws FsException {
        return loadRoot(ref).totalSize();
    }

    public byte[] getAll(FileRef ref) throws FsException {
        long size = size(ref);
        if (size > Integer.MAX_VALUE - 8) {
            throw new FsException(FsErrorCode.INTERNAL, "file too large for a byte array, use get(ref, out)");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) size);
        get(ref, out);
        return out.toByteArray();
    }

    /** Streams the whole file to {@code out}; up to {@code window} chunks are fetched concurrently. */
    public void get(FileRef ref, OutputStream out) throws FsException {
        Objects.requireNonNull(out, "out");
        Page root = loadRoot(ref);
        byte[] key = ref.key();
        Window<byte[]> pipeline = new Window<>(plain -> {
            try {
                out.write(plain);
            } catch (IOException e) {
                throw new FsException(FsErrorCode.INTERNAL, "failed to write output", e);
            }
        });
        try {
            long[] counter = {0};
            walk(root, 0, key, ref.manifestSalt(), chunk -> {
                int index = (int) counter[0]++;
                pipeline.submit(() -> readChunk(key, index, chunk));
            });
            pipeline.drain();
        } catch (RuntimeException e) {
            pipeline.abort();
            throw e;
        }
    }

    /** Reads {@code length} bytes at {@code offset} fetching only (and concurrently) the overlapping chunks. */
    public byte[] read(FileRef ref, long offset, int length) throws FsException {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("negative offset or length");
        }
        Page root = loadRoot(ref);
        long end = Math.min(root.totalSize(), offset + length);
        if (offset >= end) {
            return new byte[0];
        }
        byte[] key = ref.key();
        int cs = root.chunkSize();
        Map<String, Page> cache = new HashMap<>();
        long first = offset / cs;
        long last = (end - 1) / cs;
        List<Future<byte[]>> futures = new ArrayList<>();
        for (long ci = first; ci <= last; ci++) {
            Chunk chunk = entryAt(root, ci, key, ref.manifestSalt(), cache);
            int index = (int) ci;
            futures.add(executor.submit(() -> readChunk(key, index, chunk)));
        }
        byte[] result = new byte[(int) (end - offset)];
        for (long ci = first; ci <= last; ci++) {
            byte[] plain = await(futures.get((int) (ci - first)));
            long chunkStart = ci * cs;
            int from = (int) Math.max(0, offset - chunkStart);
            int to = (int) Math.min(plain.length, end - chunkStart);
            System.arraycopy(plain, from, result, (int) (chunkStart + from - offset), to - from);
        }
        return result;
    }

    private void walk(Page page, long pageIndex, byte[] fileKey, long salt, Consumer<Chunk> visitor) {
        if (page.isLeaf()) {
            page.chunks().forEach(visitor);
            return;
        }
        for (int j = 0; j < page.children().size(); j++) {
            long childIndex = pageIndex * page.fanout() + j;
            Page child = loadPage(page.children().get(j), page.level() - 1, childIndex, fileKey, salt, page);
            walk(child, childIndex, fileKey, salt, visitor);
        }
    }

    private Chunk entryAt(Page root, long chunkIndex, byte[] fileKey, long salt, Map<String, Page> cache) {
        Page page = root;
        long pageIndex = 0;
        int f = root.fanout();
        while (!page.isLeaf()) {
            long span = 1;
            for (int i = 0; i < page.level(); i++) {
                span *= f;
            }
            int local = (int) ((chunkIndex / span) % f);
            if (local >= page.children().size()) {
                throw new FsException(FsErrorCode.DIGEST_MISMATCH, "manifest does not cover chunk " + chunkIndex);
            }
            long childIndex = pageIndex * f + local;
            PageRef ref = page.children().get(local);
            int childLevel = page.level() - 1;
            Page parent = page;
            page = cache.computeIfAbsent(childLevel + "/" + childIndex,
                    k -> loadPage(ref, childLevel, childIndex, fileKey, salt, parent));
            pageIndex = childIndex;
        }
        int local = (int) (chunkIndex % f);
        if (local >= page.chunks().size()) {
            throw new FsException(FsErrorCode.DIGEST_MISMATCH, "manifest does not cover chunk " + chunkIndex);
        }
        return page.chunks().get(local);
    }

    private Page loadRoot(FileRef ref) {
        byte[] sealed = fetchAny(ref.rootId(), ref.rootLocs());
        byte[] plain = DataCrypto.open(DataCrypto.derive(ref.key(), rootContext(ref.manifestSalt())),
                ROOT_AAD.getBytes(StandardCharsets.UTF_8), sealed);
        return ManifestCodec.decode(plain);
    }

    private Page loadPage(PageRef ref, int level, long index, byte[] fileKey, long salt, Page parent) {
        byte[] sealed = fetchAny(ref.id(), ref.locs());
        byte[] plain = DataCrypto.open(DataCrypto.derive(fileKey, pageContext(salt, level, index)),
                pageAad(level, index).getBytes(StandardCharsets.UTF_8), sealed);
        Page page = ManifestCodec.decode(plain);
        if (page.level() != level || page.fanout() != parent.fanout() || page.chunkSize() != parent.chunkSize()) {
            throw new FsException(FsErrorCode.DIGEST_MISMATCH, "manifest page does not fit its parent");
        }
        return page;
    }

    private byte[] readChunk(byte[] fileKey, int index, Chunk chunk) {
        List<Variant> order = new ArrayList<>(chunk.variants());
        order.sort(Comparator.comparingInt(v -> v.tier().ordinal())); // HOT first

        FsException last = null;
        for (Variant v : order) {
            try {
                return readVariant(fileKey, index, v, chunk.plainLen());
            } catch (FsException e) {
                last = e;
            }
        }
        throw last != null ? last : new FsException(FsErrorCode.NOT_FOUND, "chunk " + index + " has no stored variant");
    }

    private byte[] readVariant(byte[] fileKey, int index, Variant var, int plainLen) {
        byte[] cipher;
        if (var.redundancy() instanceof Redundancy.Replicas) {
            cipher = null;
            for (BlobLoc loc : likelyFastestFirst(var.blobs())) {
                byte[] data = fetch(loc);
                if (data != null && data.length == var.cipherLen()) {
                    cipher = data;
                    break;
                }
            }
            if (cipher == null) {
                throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "no readable replica");
            }
        } else if (var.redundancy() instanceof Redundancy.ErasureCoded ec) {
            byte[][] shards = new byte[ec.k() + ec.m()][];
            int have = 0;
            List<BlobLoc> dataLocs = new ArrayList<>();
            List<BlobLoc> parityLocs = new ArrayList<>();
            for (BlobLoc loc : var.blobs()) {
                (loc.index() < ec.k() ? dataLocs : parityLocs).add(loc);
            }
            // data shards in parallel (no matrix work when all arrive), parity only to fill the gaps
            List<Future<byte[]>> pending = new ArrayList<>();
            for (BlobLoc loc : dataLocs) {
                pending.add(executor.submit(() -> fetch(loc)));
            }
            for (int i = 0; i < dataLocs.size(); i++) {
                have += accept(shards, dataLocs.get(i), await(pending.get(i)), var.shardLen());
            }
            for (int i = 0; i < parityLocs.size() && have < ec.k(); i++) {
                have += accept(shards, parityLocs.get(i), fetch(parityLocs.get(i)), var.shardLen());
            }
            if (have < ec.k()) {
                throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE,
                        "only " + have + " of the " + ec.k() + " shards needed are readable");
            }
            byte[][] data = reedSolomon(ec.k(), ec.m()).decode(shards, var.shardLen());
            byte[] joined = new byte[ec.k() * var.shardLen()];
            for (int i = 0; i < ec.k(); i++) {
                System.arraycopy(data[i], 0, joined, i * var.shardLen(), var.shardLen());
            }
            cipher = Arrays.copyOf(joined, var.cipherLen());
        } else {
            throw new FsException(FsErrorCode.INTERNAL, "unknown redundancy");
        }

        byte[] frame = DataCrypto.open(DataCrypto.derive(fileKey, chunkContext(index, var.salt())),
                chunkAad(index, var.salt(), var.tier(), var.codecId(), plainLen), cipher);
        byte[] compressed = unframe(frame);
        byte[] plain = Codecs.byId(var.codecId()).decompress(compressed, plainLen);
        if (plain.length != plainLen) {
            throw new FsException(FsErrorCode.DIGEST_MISMATCH, "chunk length mismatch");
        }
        return plain;
    }

    private static int accept(byte[][] shards, BlobLoc loc, byte[] shard, int shardLen) {
        if (shard != null && shard.length == shardLen && loc.index() >= 0 && loc.index() < shards.length
                && shards[loc.index()] == null) {
            shards[loc.index()] = shard;
            return 1;
        }
        return 0;
    }

    /**
     * Orders copies for reading: a random order biased by node speed (faster nodes first more often, so they take
     * a proportional share of the load without ever being the only choice).
     */
    private List<BlobLoc> likelyFastestFirst(List<BlobLoc> locs) {
        if (locs.size() < 2) {
            return locs;
        }
        record Ranked(BlobLoc loc, double score) {
        }
        List<Ranked> ranked = new ArrayList<>(locs.size());
        for (BlobLoc loc : locs) {
            double speed = cluster.find(loc.nodeId()).filter(StorageNode::available).map(StorageNode::speedWeight).orElse(0.0);
            double u = ThreadLocalRandom.current().nextDouble(Double.MIN_NORMAL, 1.0);
            ranked.add(new Ranked(loc, speed > 0 ? -Math.log(u) / speed : Double.POSITIVE_INFINITY));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score));
        List<BlobLoc> out = new ArrayList<>(ranked.size());
        ranked.forEach(r -> out.add(r.loc()));
        return out;
    }

    /** First verified copy among {@code locs}; falls back to asking every available node for {@code id}. */
    private byte[] fetchAny(ObjectId id, List<BlobLoc> locs) {
        for (BlobLoc loc : locs) {
            byte[] data = fetch(loc);
            if (data != null) {
                return data;
            }
        }
        for (StorageNode node : cluster.nodes()) {
            if (node.available()) {
                byte[] data = fetch(new BlobLoc(node.id(), id, 0));
                if (data != null) {
                    return data;
                }
            }
        }
        throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE, "no readable copy of " + id.hex());
    }

    /** Fetches one blob and checks it against its content hash; null if missing, unreachable or corrupt. */
    private byte[] fetch(BlobLoc loc) {
        StorageNode node = cluster.find(loc.nodeId()).orElse(null);
        if (node == null || !node.available()) {
            return null;
        }
        try {
            byte[] data = node.store().get(loc.blobId());
            return ContentDigest.sha256(data).equals(loc.blobId()) ? data : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Repair
    // ════════════════════════════════════════════════════════════════════════════

    private enum Health { OK, UNKNOWN, LOST, CORRUPT }

    /** Mutable counters of one repair run (chunks are repaired concurrently). */
    private static final class RepairStats {
        final java.util.concurrent.atomic.AtomicInteger rebuilt = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger recreated = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger lost = new java.util.concurrent.atomic.AtomicInteger();
    }

    /** Repairs {@code ref} treating every unavailable node as lost and without reading blobs back. */
    public RepairReport repair(FileRef ref) throws FsException {
        return repair(ref, false, id -> cluster.find(id).map(n -> !n.available()).orElse(true));
    }

    /**
     * Restores the redundancy of a file after nodes were lost or blobs went bad.
     *
     * <p>Missing replicas are copied from a survivor, missing erasure-coded shards are recomputed from any
     * {@code k} others (no key needed for that), and a variant too damaged to rebuild is recreated from
     * another variant (this does use the file key). Manifest pages get their lost copies back too.
     *
     * @param scrub    also read every blob back and verify its hash, finding silent corruption; much slower
     * @param nodeLost decides which nodes count as gone for good (see {@link com.redactado.noray.data.placement.NodeWatch});
     *                 pieces on unavailable nodes that are not lost are assumed to be fine
     */
    public RepairReport repair(FileRef ref, boolean scrub, java.util.function.Predicate<String> nodeLost) throws FsException {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(nodeLost, "nodeLost");
        com.redactado.noray.kernel.metrics.Metrics.global().inc("data.repairs");
        try {
            return doRepair(ref, scrub, nodeLost);
        } finally {
            suspects.clear();
        }
    }

    private RepairReport doRepair(FileRef ref, boolean scrub, java.util.function.Predicate<String> nodeLost) {
        byte[] key = ref.key();

        // phase 1: audit. Page copies are restored in place (readers find pages by id), chunk damage is collected.
        java.util.Set<Long> damaged = new java.util.TreeSet<>();
        Set<Tier> usedTiers = new TreeSet<>(Comparator.comparingInt(Tier::ordinal));
        long[] counters = {0, 0}; // chunks, pagesRestored
        walkPages(ref, visit -> {
            counters[1] += restorePage(visit.ref(), scrub, nodeLost);
            for (Chunk chunk : visit.page().chunks()) {
                long index = counters[0]++;
                chunk.variants().forEach(v -> usedTiers.add(v.tier()));
                for (Variant v : chunk.variants()) {
                    if (v.blobs().stream().anyMatch(b -> {
                        Health h = health(b, scrub, nodeLost);
                        return h == Health.LOST || h == Health.CORRUPT;
                    })) {
                        damaged.add(index);
                        break;
                    }
                }
            }
        });
        int chunks = (int) counters[0];
        int pagesRestored = (int) counters[1];
        if (damaged.isEmpty()) {
            return new RepairReport(ref, false, chunks, 0, 0, 0, pagesRestored, 0);
        }

        // phase 2: rebuild the tree with repaired chunks
        Page root = loadRoot(ref);
        StoragePolicy base = defaultPolicy;
        BlobLog written = new BlobLog();
        BlobLog obsolete = new BlobLog();
        RepairStats stats = new RepairStats();
        TreeBuilder tree = new TreeBuilder(key, RANDOM.nextLong(), root.chunkSize(), root.fanout(),
                List.copyOf(usedTiers), base.metadataReplicas(), written);
        Window<Chunk> pipeline = new Window<>(tree::add);
        try {
            long[] counter = {0};
            walk(root, 0, key, ref.manifestSalt(), chunk -> {
                int index = (int) counter[0]++;
                if (damaged.contains((long) index)) {
                    pipeline.submit(() -> repairChunk(key, index, chunk, scrub, nodeLost, base, written, obsolete, stats));
                } else {
                    pipeline.submit(() -> chunk);
                }
            });
            pipeline.drain();
            FileRef result = tree.finish(root.totalSize());
            discard(obsolete);
            return new RepairReport(result, true, chunks, damaged.size(), stats.rebuilt.get(), stats.recreated.get(),
                    pagesRestored, stats.lost.get());
        } catch (RuntimeException e) {
            pipeline.abort();
            discard(written);
            throw e;
        }
    }

    private Health health(BlobLoc loc, boolean scrub, java.util.function.Predicate<String> nodeLost) {
        StorageNode node = cluster.find(loc.nodeId()).orElse(null);
        if (node == null) {
            return Health.LOST;
        }
        if (!node.available()) {
            return nodeLost.test(node.id()) ? Health.LOST : Health.UNKNOWN;
        }
        try {
            if (!node.store().has(loc.blobId())) {
                return Health.LOST;
            }
            if (scrub && fetch(loc) == null) {
                suspects.add(node.id());
                return Health.CORRUPT;
            }
            return Health.OK;
        } catch (RuntimeException e) {
            return Health.UNKNOWN; // could not even ask: do not start moving data because of that
        }
    }

    /** Ensures a page has as many readable copies as it was written with. Returns 1 if copies were added. */
    private int restorePage(PageRef page, boolean scrub, java.util.function.Predicate<String> nodeLost) {
        int wanted = page.locs().size();
        Set<String> holders = new HashSet<>();
        byte[] source = null;
        for (StorageNode node : cluster.nodes()) {
            if (node.available()) {
                try {
                    if (node.store().has(page.id())) {
                        byte[] bytes = (scrub || source == null) ? fetch(new BlobLoc(node.id(), page.id(), 0)) : null;
                        if (scrub && bytes == null) {
                            node.store().delete(page.id()); // corrupt copy: remove it, a good one is written below
                            suspects.add(node.id());
                            continue;
                        }
                        holders.add(node.id());
                        if (source == null) {
                            source = bytes;
                        }
                    }
                } catch (RuntimeException ignored) {
                    // unreadable node: not counted as a holder
                }
            } else if (page.locs().stream().anyMatch(l -> l.nodeId().equals(node.id())) && !nodeLost.test(node.id())) {
                holders.add(node.id()); // down but expected back
            }
        }
        if (holders.size() >= wanted || source == null) {
            return 0;
        }
        List<StorageNode> pool = new ArrayList<>();
        for (StorageNode n : nodesOf(Tier.HOT)) {
            if (!holders.contains(n.id())) {
                pool.add(n);
            }
        }
        int missing = Math.min(wanted - holders.size(), pool.size());
        if (missing < 1) {
            return 0;
        }
        List<byte[]> pieces = new ArrayList<>();
        for (int i = 0; i < missing; i++) {
            pieces.add(source);
        }
        storePieces(pieces, pool, page.id().hex(), Tier.HOT, new HashSet<>(), new BlobLog());
        return 1;
    }

    private Chunk repairChunk(byte[] key, int index, Chunk chunk, boolean scrub, java.util.function.Predicate<String> nodeLost,
                              StoragePolicy policy, Collection<BlobLoc> written, Collection<BlobLoc> obsolete,
                              RepairStats stats) {
        List<Variant> result = new ArrayList<>(chunk.variants().size());
        byte[] plain = null;
        boolean unreadable = false;
        for (int v = 0; v < chunk.variants().size(); v++) {
            Variant var = chunk.variants().get(v);
            Health[] states = new Health[var.blobs().size()];
            boolean damaged = false;
            for (int i = 0; i < states.length; i++) {
                states[i] = health(var.blobs().get(i), scrub, nodeLost);
                damaged |= states[i] == Health.LOST || states[i] == Health.CORRUPT;
            }
            if (!damaged) {
                result.add(var);
                continue;
            }
            Set<String> avoid = new HashSet<>();
            for (int o = 0; o < chunk.variants().size(); o++) {
                if (o != v) {
                    chunk.variants().get(o).blobs().forEach(b -> avoid.add(b.nodeId()));
                }
            }
            Variant rebuilt = rebuildVariant(var, states, avoid, written, obsolete, stats);
            if (rebuilt == null) { // cannot heal from itself: recreate from the plaintext, if any variant still reads
                if (plain == null && !unreadable) {
                    try {
                        plain = readChunk(key, index, chunk);
                    } catch (FsException e) {
                        unreadable = true;
                    }
                }
                if (plain == null) {
                    stats.lost.incrementAndGet();
                    result.add(var);
                    continue;
                }
                StoragePolicy.Variant want = new StoragePolicy.Variant(var.tier(), var.codecId(), var.redundancy());
                rebuilt = createVariant(key, index, plain, want, policy, avoid, written);
                for (int i = 0; i < states.length; i++) {
                    obsolete.add(var.blobs().get(i)); // whatever is left of the old variant is useless now
                }
                stats.recreated.incrementAndGet();
            }
            result.add(rebuilt);
        }
        return new Chunk(chunk.plainLen(), result);
    }

    /** Rebuilds the lost pieces of a variant from its survivors; null if too few survive. */
    private Variant rebuildVariant(Variant var, Health[] states, Set<String> avoid, Collection<BlobLoc> written,
                                   Collection<BlobLoc> obsolete, RepairStats stats) {
        List<BlobLoc> blobs = var.blobs();
        List<BlobLoc> kept = new ArrayList<>();
        List<Integer> lostIndexes = new ArrayList<>();
        for (int i = 0; i < blobs.size(); i++) {
            if (states[i] == Health.LOST || states[i] == Health.CORRUPT) {
                if (states[i] == Health.CORRUPT) {
                    obsolete.add(blobs.get(i)); // free the space of the bad copy
                    avoid.add(blobs.get(i).nodeId()); // and do not put the replacement on a node that serves bad data
                }
                lostIndexes.add(blobs.get(i).index());
            } else {
                kept.add(blobs.get(i));
                avoid.add(blobs.get(i).nodeId());
            }
        }
        List<StorageNode> pool = nodesOf(var.tier());
        List<byte[]> pieces = new ArrayList<>();

        if (var.redundancy() instanceof Redundancy.Replicas) {
            byte[] source = null;
            for (BlobLoc loc : kept) {
                byte[] data = fetch(loc);
                if (data != null && data.length == var.cipherLen()) {
                    source = data;
                    break;
                }
            }
            if (source == null) {
                return null;
            }
            for (int i = 0; i < lostIndexes.size(); i++) {
                pieces.add(source);
            }
        } else if (var.redundancy() instanceof Redundancy.ErasureCoded ec) {
            byte[][] shards = new byte[ec.k() + ec.m()][];
            int have = 0;
            for (BlobLoc loc : kept) {
                if (have == ec.k()) {
                    break;
                }
                have += accept(shards, loc, fetch(loc), var.shardLen());
            }
            if (have < ec.k()) {
                return null;
            }
            byte[][] data = reedSolomon(ec.k(), ec.m()).decode(shards, var.shardLen());
            byte[][] parity = reedSolomon(ec.k(), ec.m()).encode(data);
            for (int index : lostIndexes) {
                pieces.add(index < ec.k() ? data[index] : parity[index - ec.k()]);
            }
        } else {
            return null;
        }

        if (pool.isEmpty()) {
            return null;
        }
        List<BlobLoc> added = storePieces(pieces, pool, ContentDigest.sha256(pieces.get(0)).hex(), var.tier(), avoid, written);
        // storePieces numbers its output 0..n-1: give the new pieces the identity of the ones they replace
        List<BlobLoc> all = new ArrayList<>(kept);
        for (int i = 0; i < added.size(); i++) {
            BlobLoc a = added.get(i);
            all.add(new BlobLoc(a.nodeId(), a.blobId(), lostIndexes.get(i)));
        }
        all.sort(Comparator.comparingInt(BlobLoc::index));
        if (var.redundancy() instanceof Redundancy.Replicas) { // replicas are interchangeable: renumber 0..n-1
            List<BlobLoc> renum = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                renum.add(new BlobLoc(all.get(i).nodeId(), all.get(i).blobId(), i));
            }
            all = renum;
        }
        stats.rebuilt.addAndGet(pieces.size());
        return new Variant(var.tier(), var.codecId(), var.redundancy(), var.cipherLen(), var.shardLen(), var.salt(), all);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Garbage collection
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Deletes blobs on engine nodes that no file needs: pieces of overwritten or deleted files, orphans of failed
     * writes, old manifests. {@code live} must contain every blob id of every file in the system (see
     * {@link #liveBlobs}); an incomplete set <b>destroys data</b>, so callers must abort if they cannot build it.
     *
     * <p>Blobs written less than {@code grace} ago by this engine are never touched, so a file being written
     * (not yet in the caller's namespace) is safe. Only call this on nodes dedicated to the engine.
     */
    public GcReport collectGarbage(Set<ObjectId> live, java.time.Duration grace, boolean dryRun) {
        Objects.requireNonNull(live, "live");
        long cutoff = System.currentTimeMillis() - grace.toMillis();
        recentWrites.values().removeIf(t -> t < cutoff);
        int scanned = 0;
        long seen = 0, deleted = 0, bytes = 0, protectedCount = 0;
        List<String> skipped = new ArrayList<>();
        for (StorageNode node : cluster.nodes()) {
            if (!node.available()) {
                skipped.add(node.id());
                continue;
            }
            Set<ObjectId> ids;
            try {
                ids = node.store().listIds();
            } catch (RuntimeException e) {
                skipped.add(node.id());
                continue;
            }
            scanned++;
            for (ObjectId id : ids) {
                seen++;
                if (live.contains(id)) {
                    continue;
                }
                if (recentWrites.containsKey(id)) {
                    protectedCount++;
                    continue;
                }
                try {
                    long size = node.store().size(id);
                    if (!dryRun) {
                        node.store().delete(id);
                    }
                    deleted++;
                    bytes += size;
                } catch (RuntimeException ignored) {
                    // vanished or undeletable: next run
                }
            }
        }
        return new GcReport(scanned, seen, deleted, bytes, protectedCount, skipped, dryRun);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Inventory and deletion
    // ════════════════════════════════════════════════════════════════════════════

    /** A manifest page together with where it was recorded. */
    private record PageVisit(PageRef ref, Page page) {
    }

    private void walkPages(FileRef ref, Consumer<PageVisit> visitor) {
        Page root = loadRoot(ref);
        PageRef rootRef = new PageRef(ref.rootId(), ref.rootLocs());
        visitor.accept(new PageVisit(rootRef, root));
        walkChildren(root, 0, ref.key(), ref.manifestSalt(), visitor);
    }

    private void walkChildren(Page page, long pageIndex, byte[] key, long salt, Consumer<PageVisit> visitor) {
        for (int j = 0; j < page.children().size(); j++) {
            long childIndex = pageIndex * page.fanout() + j;
            PageRef childRef = page.children().get(j);
            Page child = loadPage(childRef, page.level() - 1, childIndex, key, salt, page);
            visitor.accept(new PageVisit(childRef, child));
            walkChildren(child, childIndex, key, salt, visitor);
        }
    }

    /** Ids of every blob (manifest pages and chunk pieces) that {@code ref} needs: the live set for garbage collection. */
    public Set<ObjectId> liveBlobs(FileRef ref) throws FsException {
        Set<ObjectId> ids = new HashSet<>();
        walkPages(ref, v -> {
            ids.add(v.ref().id());
            for (Chunk c : v.page().chunks()) {
                for (Variant var : c.variants()) {
                    var.blobs().forEach(b -> ids.add(b.blobId()));
                }
            }
        });
        return ids;
    }

    /** Ids of the nodes that hold any piece or manifest copy {@code ref} points to. */
    public Set<String> nodesUsed(FileRef ref) throws FsException {
        Set<String> nodes = new TreeSet<>();
        walkPages(ref, v -> {
            v.ref().locs().forEach(l -> nodes.add(l.nodeId()));
            for (Chunk c : v.page().chunks()) {
                c.variants().forEach(var -> var.blobs().forEach(b -> nodes.add(b.nodeId())));
            }
        });
        return nodes;
    }

    /** Best-effort removal of everything stored for {@code ref}: pages and chunk pieces. */
    public void delete(FileRef ref) throws FsException {
        List<BlobLoc> all = new ArrayList<>();
        walkPages(ref, v -> {
            all.addAll(v.ref().locs());
            for (Chunk c : v.page().chunks()) {
                for (Variant var : c.variants()) {
                    all.addAll(var.blobs());
                }
            }
        });
        discard(all);
    }

    /**
     * Removes only the manifest pages of {@code ref}, keeping the chunk pieces. Used after {@link #retier} or a
     * repair produced a new manifest that shares those pieces with the old one.
     */
    public void deleteManifest(FileRef ref) throws FsException {
        List<BlobLoc> pages = new ArrayList<>();
        walkPages(ref, v -> pages.addAll(v.ref().locs()));
        discard(pages);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════════

    /** Bounded in-order pipeline: tasks run concurrently, results reach the sink in submission order. */
    private final class Window<T> {
        private final ArrayDeque<Future<T>> queue = new ArrayDeque<>();
        private final Consumer<T> sink;

        Window(Consumer<T> sink) {
            this.sink = sink;
        }

        void submit(Callable<T> task) {
            queue.add(executor.submit(task));
            if (queue.size() >= window) {
                sink.accept(await(queue.poll()));
            }
        }

        void drain() {
            while (!queue.isEmpty()) {
                sink.accept(await(queue.poll()));
            }
        }

        /** Waits for in-flight tasks (so that cleanup sees everything they wrote) and forgets their results. */
        void abort() {
            for (Future<T> f : queue) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException ignored) {
                    // the first failure is already being reported
                }
            }
            queue.clear();
        }
    }

    /** Thread-safe log of blob locations written by an operation. */
    private static final class BlobLog extends ConcurrentLinkedQueue<BlobLoc> {
        private static final long serialVersionUID = 1L;
    }

    private static <T> T await(Future<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FsException(FsErrorCode.INTERNAL, "interrupted", e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException r) {
                throw r;
            }
            if (c instanceof Error err) {
                throw err;
            }
            throw new FsException(FsErrorCode.INTERNAL, String.valueOf(c), c);
        }
    }

    private List<StorageNode> nodesOf(Tier tier) {
        List<StorageNode> out = new ArrayList<>();
        for (StorageNode n : cluster.nodes()) {
            if (n.available() && n.accepts(tier) && !suspects.contains(n.id())) {
                out.add(n);
            }
        }
        return out;
    }

    private ReedSolomon reedSolomon(int k, int m) {
        return rsCache.computeIfAbsent(((long) k << 32) | m, key -> new ReedSolomon(k, m));
    }

    private static String chunkContext(int index, long salt) {
        return "noray/chunk/v2|" + index + "|" + Long.toHexString(salt);
    }

    private static byte[] chunkAad(int index, long salt, Tier tier, int codecId, int plainLen) {
        return ("chunk|" + index + "|" + Long.toHexString(salt) + "|" + tier.wireCode() + "|" + codecId + "|" + plainLen)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String pageContext(long salt, int level, long index) {
        return "noray/page/v2|" + Long.toHexString(salt) + "|" + level + "|" + index;
    }

    private static String rootContext(long salt) {
        return "noray/root/v2|" + Long.toHexString(salt);
    }

    private static String pageAad(int level, long index) {
        return "page|" + level + "|" + index;
    }

    /** {@code [len:int][data][zero padding]}; the padded length only reveals a size bucket. */
    static byte[] frame(byte[] data, boolean pad) {
        int raw = 4 + data.length;
        ByteBuffer buf = ByteBuffer.allocate(pad ? bucket(raw) : raw);
        buf.putInt(data.length);
        buf.put(data);
        return buf.array();
    }

    static byte[] unframe(byte[] frame) {
        if (frame.length < 4) {
            throw new FsException(FsErrorCode.DIGEST_MISMATCH, "frame too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(frame);
        int len = buf.getInt();
        if (len < 0 || len > frame.length - 4) {
            throw new FsException(FsErrorCode.DIGEST_MISMATCH, "invalid frame length");
        }
        byte[] out = new byte[len];
        buf.get(out);
        return out;
    }

    /** Rounds up to the next of 8 evenly spaced sizes per power of two (at most 12.5% overhead). */
    static int bucket(int len) {
        if (len <= 256) {
            return 256;
        }
        int e = 31 - Integer.numberOfLeadingZeros(len - 1);
        int base = 1 << e;
        int step = Math.max(1, base / 8);
        int steps = (len - base + step - 1) / step;
        return base + steps * step;
    }
}
