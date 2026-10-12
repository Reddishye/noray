package com.redactado.noray.filesystem.store.remote;

import com.redactado.noray.filesystem.store.ObjectId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Wire format for the FS_BLOB channel. Every frame starts with {@code op(1) requestId(4)};
 * replies MUST echo the requestId of the request they answer so the requester can correlate them.
 */
public final class BlobProtocol {

    public static final byte HAS = 1;
    public static final byte GET = 2;
    public static final byte PUT = 3;
    public static final byte HAS_REPLY = 4;
    public static final byte GET_REPLY = 5;
    public static final byte PUT_REPLY = 6;
    public static final byte ERR = 7;
    public static final byte DELETE = 8;
    public static final byte LIST = 9;
    public static final byte SIZE = 10;
    public static final byte DELETE_REPLY = 11;
    public static final byte LIST_REPLY = 12;
    public static final byte SIZE_REPLY = 13;

    /** Ids per LIST reply (32 bytes each): bounded so that one reply is a reasonable frame. */
    public static final int LIST_PAGE = 4096;

    /** Minimum size of any frame: op + requestId. */
    public static final int HEADER_SIZE = 5;

    private static final HexFormat HEX = HexFormat.of();

    private BlobProtocol() {
    }

    // ── Requests ───────────────────────────────────────────────────────────────

    public static byte[] encodeHas(int requestId, ObjectId id) {
        return encodeDigest(HAS, requestId, id);
    }

    public static byte[] encodeGet(int requestId, ObjectId id) {
        return encodeDigest(GET, requestId, id);
    }

    public static byte[] encodePut(int requestId, byte[] data) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(PUT);
            out.writeInt(requestId);
            out.writeInt(data.length);
            out.write(data);
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodeDelete(int requestId, ObjectId id) {
        return encodeDigest(DELETE, requestId, id);
    }

    public static byte[] encodeSize(int requestId, ObjectId id) {
        return encodeDigest(SIZE, requestId, id);
    }

    /** Asks for up to {@link #LIST_PAGE} ids greater than {@code after} in id order ({@code null} = from the start). */
    public static byte[] encodeList(int requestId, ObjectId after) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(LIST);
            out.writeInt(requestId);
            out.writeBoolean(after != null);
            if (after != null) {
                out.write(HEX.parseHex(after.hex()));
            }
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Replies ────────────────────────────────────────────────────────────────

    public static byte[] encodeHasReply(int requestId, boolean present) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(HAS_REPLY);
            out.writeInt(requestId);
            out.writeBoolean(present);
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodeGetReply(int requestId, byte[] data) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(GET_REPLY);
            out.writeInt(requestId);
            out.writeInt(data.length);
            out.write(data);
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodePutReply(int requestId, ObjectId id) {
        return encodeDigest(PUT_REPLY, requestId, id);
    }

    public static byte[] encodeDeleteReply(int requestId) {
        return new byte[]{DELETE_REPLY, (byte) (requestId >>> 24), (byte) (requestId >>> 16), (byte) (requestId >>> 8),
                (byte) requestId};
    }

    public static byte[] encodeSizeReply(int requestId, long size) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(SIZE_REPLY);
            out.writeInt(requestId);
            out.writeLong(size);
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodeListReply(int requestId, java.util.List<ObjectId> ids, boolean more) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(LIST_REPLY);
            out.writeInt(requestId);
            out.writeBoolean(more);
            out.writeInt(ids.size());
            for (ObjectId id : ids) {
                out.write(HEX.parseHex(id.hex()));
            }
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodeError(int requestId, int fsErrorCodeOrdinal) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(ERR);
            out.writeInt(requestId);
            out.writeInt(fsErrorCodeOrdinal);
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodeDigest(byte op, int requestId, ObjectId id) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(op);
            out.writeInt(requestId);
            out.write(HEX.parseHex(id.hex()));
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Decoding ───────────────────────────────────────────────────────────────

    public static byte decodeOp(byte[] payload) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            return in.readByte();
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static int decodeRequestId(byte[] payload) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            in.readByte();
            return in.readInt();
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static ObjectId decodeDigest(byte[] payload) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            in.readByte();
            in.readInt();
            byte[] raw = in.readNBytes(32);
            if (raw.length != 32) {
                throw new IllegalArgumentException("truncated digest");
            }
            return new ObjectId(HEX.formatHex(raw));
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static byte[] decodeData(byte[] payload) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            in.readByte(); // op
            in.readInt(); // requestId
            int len = in.readInt(); // data length
            if (len < 0 || len > payload.length - HEADER_SIZE - 4) {
                throw new IllegalArgumentException("invalid data length: " + len);
            }
            return in.readNBytes(len);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static byte[] decodePutData(byte[] payload) {
        return decodeData(payload);
    }

    public static boolean decodeBoolean(byte[] payload) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            in.readByte();
            in.readInt();
            return in.readBoolean();
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static long decodeSize(byte[] payload) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            in.readByte();
            in.readInt();
            return in.readLong();
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** The cursor of a LIST request, or {@code null} when listing from the start. */
    public static ObjectId decodeListCursor(byte[] payload) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            in.readByte();
            in.readInt();
            if (!in.readBoolean()) {
                return null;
            }
            byte[] raw = in.readNBytes(32);
            if (raw.length != 32) {
                throw new IllegalArgumentException("truncated cursor");
            }
            return new ObjectId(HEX.formatHex(raw));
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public record ListPage(java.util.List<ObjectId> ids, boolean more) {
    }

    public static ListPage decodeListReply(byte[] payload) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            in.readByte();
            in.readInt();
            boolean more = in.readBoolean();
            int n = in.readInt();
            if (n < 0 || n > LIST_PAGE || (long) n * 32 > payload.length) {
                throw new IllegalArgumentException("invalid id count: " + n);
            }
            java.util.List<ObjectId> ids = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                byte[] raw = in.readNBytes(32);
                if (raw.length != 32) {
                    throw new IllegalArgumentException("truncated id list");
                }
                ids.add(new ObjectId(HEX.formatHex(raw)));
            }
            return new ListPage(ids, more);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static int decodeErrorCode(byte[] payload) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            in.readByte();
            in.readInt();
            return in.readInt();
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static ObjectId randomObjectId() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return new ObjectId(HEX.formatHex(raw));
    }
}
