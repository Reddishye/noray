package com.redactado.noray.data.manifest;

import com.redactado.noray.data.manifest.Manifest.BlobLoc;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.ObjectId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Handle to a stored file: the id of its root manifest page plus the secret key that opens everything below it.
 *
 * <p><b>The key makes this value a secret.</b> Whoever has it can read the file; whoever does not can read
 * nothing, not even the file's name or size, from the storage nodes.
 *
 * @param rootLocs     hints about where the root page is stored (optional but speeds up the first read)
 * @param manifestSalt public per-tree value mixed into manifest page keys, so that a re-tiered tree (new pages,
 *                     same data key) never reuses a page key
 */
public record FileRef(ObjectId rootId, List<BlobLoc> rootLocs, byte[] key, long manifestSalt) {

    private static final HexFormat HEX = HexFormat.of();
    private static final String PREFIX = "noray-ref:2:";

    public FileRef {
        Objects.requireNonNull(rootId, "rootId");
        rootLocs = List.copyOf(rootLocs);
        Objects.requireNonNull(key, "key");
        if (key.length != 32) {
            throw new IllegalArgumentException("key must be 32 bytes");
        }
        key = key.clone();
    }

    @Override
    public byte[] key() {
        return key.clone();
    }

    /** Serialises to a single-line string. Contains the secret key. */
    public String encode() {
        StringBuilder sb = new StringBuilder(PREFIX).append(rootId.hex()).append(':').append(HEX.formatHex(key)).append(':')
                .append(Long.toHexString(manifestSalt)).append(':');
        for (int i = 0; i < rootLocs.size(); i++) {
            BlobLoc l = rootLocs.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(l.nodeId()).append('|').append(l.index());
        }
        return sb.toString();
    }

    public static FileRef parse(String text) {
        try {
            if (!text.startsWith(PREFIX)) {
                throw new IllegalArgumentException("missing prefix");
            }
            String[] parts = text.substring(PREFIX.length()).split(":", 4);
            ObjectId id = new ObjectId(parts[0]);
            byte[] key = HEX.parseHex(parts[1]);
            long salt = Long.parseUnsignedLong(parts[2], 16);
            List<BlobLoc> locs = new ArrayList<>();
            if (parts.length > 3 && !parts[3].isEmpty()) {
                for (String item : parts[3].split(",")) {
                    int bar = item.lastIndexOf('|');
                    locs.add(new BlobLoc(item.substring(0, bar), id, Integer.parseInt(item.substring(bar + 1))));
                }
            }
            return new FileRef(id, locs, key, salt);
        } catch (RuntimeException e) {
            throw new FsException(FsErrorCode.INVALID_PATH, "invalid file reference: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FileRef f && rootId.equals(f.rootId) && rootLocs.equals(f.rootLocs)
                && manifestSalt == f.manifestSalt && Arrays.equals(key, f.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rootId, rootLocs, manifestSalt) * 31 + Arrays.hashCode(key);
    }

    @Override
    public String toString() {
        return "FileRef[" + rootId.hex().substring(0, 12) + "…, key=<hidden>]";
    }
}
