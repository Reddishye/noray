package com.redactado.noray.data.manifest;

import com.redactado.noray.data.manifest.Manifest.BlobLoc;
import com.redactado.noray.data.manifest.Manifest.Chunk;
import com.redactado.noray.data.manifest.Manifest.Page;
import com.redactado.noray.data.manifest.Manifest.PageRef;
import com.redactado.noray.data.manifest.Manifest.Variant;
import com.redactado.noray.data.placement.Tier;
import com.redactado.noray.data.redundancy.Redundancy;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.ObjectId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Binary (de)serialisation of manifest pages. Explicit codes only, never enum ordinals. */
public final class ManifestCodec {

    private static final int MAGIC = 0x4e52594d; // "NRYM"
    private static final int VERSION = 1;
    private static final int MAX_ITEMS = 1 << 20;
    private static final HexFormat HEX = HexFormat.of();

    private static final int RED_REPLICAS = 1;
    private static final int RED_ERASURE = 2;

    private ManifestCodec() {
    }

    public static byte[] encode(Page page) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeByte(page.level());
            out.writeInt(page.chunkSize());
            out.writeInt(page.fanout());
            out.writeLong(page.totalSize());
            if (page.isLeaf()) {
                out.writeInt(page.chunks().size());
                for (Chunk c : page.chunks()) {
                    writeChunk(out, c);
                }
            } else {
                out.writeInt(page.children().size());
                for (PageRef r : page.children()) {
                    out.write(HEX.parseHex(r.id().hex()));
                    writeLocs(out, r.locs());
                }
            }
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Page decode(byte[] bytes) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC) {
                throw new IllegalArgumentException("not a manifest page");
            }
            int version = in.readUnsignedByte();
            if (version != VERSION) {
                throw new IllegalArgumentException("unsupported manifest version " + version);
            }
            int level = in.readUnsignedByte();
            int chunkSize = in.readInt();
            int fanout = in.readInt();
            if (fanout < 2 || fanout > 65536) {
                throw new IllegalArgumentException("invalid fanout " + fanout);
            }
            long totalSize = in.readLong();
            int count = in.readInt();
            if (count < 0 || count > MAX_ITEMS) {
                throw new IllegalArgumentException("invalid item count " + count);
            }
            List<Chunk> chunks = new ArrayList<>();
            List<PageRef> children = new ArrayList<>();
            if (level == 0) {
                for (int i = 0; i < count; i++) {
                    chunks.add(readChunk(in));
                }
            } else {
                for (int i = 0; i < count; i++) {
                    ObjectId id = readId(in);
                    children.add(new PageRef(id, readLocs(in)));
                }
            }
            return new Page(level, chunkSize, fanout, totalSize, chunks, children);
        } catch (IOException | RuntimeException e) {
            throw new FsException(FsErrorCode.DIGEST_MISMATCH, "corrupt manifest page: " + e.getMessage(), e);
        }
    }

    private static void writeChunk(DataOutputStream out, Chunk c) throws IOException {
        out.writeInt(c.plainLen());
        out.writeByte(c.variants().size());
        for (Variant v : c.variants()) {
            out.writeByte(v.tier().wireCode());
            out.writeByte(v.codecId());
            switch (v.redundancy()) {
                case Redundancy.Replicas r -> {
                    out.writeByte(RED_REPLICAS);
                    out.writeByte(r.n());
                    out.writeByte(0);
                }
                case Redundancy.ErasureCoded e -> {
                    out.writeByte(RED_ERASURE);
                    out.writeByte(e.k());
                    out.writeByte(e.m());
                }
            }
            out.writeInt(v.cipherLen());
            out.writeInt(v.shardLen());
            out.writeLong(v.salt());
            writeLocs(out, v.blobs());
        }
    }

    private static Chunk readChunk(DataInputStream in) throws IOException {
        int plainLen = in.readInt();
        int variantCount = in.readUnsignedByte();
        List<Variant> variants = new ArrayList<>(variantCount);
        for (int i = 0; i < variantCount; i++) {
            Tier tier = Tier.fromWireCode(in.readUnsignedByte());
            int codecId = in.readUnsignedByte();
            int type = in.readUnsignedByte();
            int a = in.readUnsignedByte();
            int b = in.readUnsignedByte();
            Redundancy red = switch (type) {
                case RED_REPLICAS -> Redundancy.replicas(a);
                case RED_ERASURE -> Redundancy.erasure(a, b);
                default -> throw new IllegalArgumentException("unknown redundancy type " + type);
            };
            int cipherLen = in.readInt();
            int shardLen = in.readInt();
            long salt = in.readLong();
            variants.add(new Variant(tier, codecId, red, cipherLen, shardLen, salt, readLocs(in)));
        }
        return new Chunk(plainLen, variants);
    }

    private static void writeLocs(DataOutputStream out, List<BlobLoc> locs) throws IOException {
        out.writeShort(locs.size());
        for (BlobLoc l : locs) {
            byte[] node = l.nodeId().getBytes(StandardCharsets.UTF_8);
            out.writeShort(node.length);
            out.write(node);
            out.write(HEX.parseHex(l.blobId().hex()));
            out.writeShort(l.index());
        }
    }

    private static List<BlobLoc> readLocs(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        List<BlobLoc> locs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int len = in.readUnsignedShort();
            byte[] node = in.readNBytes(len);
            if (node.length != len) {
                throw new IOException("truncated node id");
            }
            ObjectId id = readId(in);
            locs.add(new BlobLoc(new String(node, StandardCharsets.UTF_8), id, in.readUnsignedShort()));
        }
        return locs;
    }

    private static ObjectId readId(DataInputStream in) throws IOException {
        byte[] raw = in.readNBytes(32);
        if (raw.length != 32) {
            throw new IOException("truncated object id");
        }
        return new ObjectId(HEX.formatHex(raw));
    }
}
