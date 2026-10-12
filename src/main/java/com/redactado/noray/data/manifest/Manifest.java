package com.redactado.noray.data.manifest;

import com.redactado.noray.data.placement.Tier;
import com.redactado.noray.data.redundancy.Redundancy;
import com.redactado.noray.filesystem.store.ObjectId;

import java.util.List;
import java.util.Objects;

/**
 * In-memory model of the Merkle tree that describes a stored file. Pages are immutable, encrypted blobs;
 * a page is addressed by the hash of its ciphertext, so the root id commits to the whole file.
 */
public final class Manifest {

    private Manifest() {
    }

    /** Where one blob lives. {@code index} is the replica number or the shard number. */
    public record BlobLoc(String nodeId, ObjectId blobId, int index) {
        public BlobLoc {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(blobId, "blobId");
        }
    }

    /**
     * One stored representation of a chunk.
     *
     * @param cipherLen length of the sealed (encrypted) chunk, before it is split into shards
     * @param shardLen  length of each shard for erasure coded variants, 0 for replicas
     * @param salt      random public value mixed into this variant's key, so that several variants of the same
     *                  chunk (and variants created later by re-tiering) never encrypt under the same key
     */
    public record Variant(Tier tier, int codecId, Redundancy redundancy, int cipherLen, int shardLen, long salt,
                          List<BlobLoc> blobs) {
        public Variant {
            Objects.requireNonNull(tier, "tier");
            Objects.requireNonNull(redundancy, "redundancy");
            blobs = List.copyOf(blobs);
        }
    }

    /** One chunk of the file, with every variant it was stored as. */
    public record Chunk(int plainLen, List<Variant> variants) {
        public Chunk {
            variants = List.copyOf(variants);
        }
    }

    /** Reference to a child page from an index page. */
    public record PageRef(ObjectId id, List<BlobLoc> locs) {
        public PageRef {
            Objects.requireNonNull(id, "id");
            locs = List.copyOf(locs);
        }
    }

    /**
     * A page of the tree. Level 0 pages list chunks; higher levels list pages of the level below.
     *
     * @param fanout    maximum number of items per page; fixed for the whole tree (needed to navigate it)
     * @param totalSize size of the whole file (the same value in every page, authoritative in the root)
     */
    public record Page(int level, int chunkSize, int fanout, long totalSize, List<Chunk> chunks, List<PageRef> children) {
        public Page {
            chunks = List.copyOf(chunks);
            children = List.copyOf(children);
        }

        public boolean isLeaf() {
            return level == 0;
        }
    }
}
