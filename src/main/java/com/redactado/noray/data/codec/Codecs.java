package com.redactado.noray.data.codec;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Registry of codecs and the built-in ones (all backed by the JDK, no extra dependency). */
public final class Codecs {

    public static final int NONE_ID = 0;
    public static final int DEFLATE_FAST_ID = 1;
    public static final int DEFLATE_MAX_ID = 2;

    /** No compression. */
    public static final Codec NONE = new Codec() {
        @Override public int id() { return NONE_ID; }
        @Override public String name() { return "none"; }
        @Override public byte[] compress(byte[] plain) { return plain.clone(); }
        @Override public byte[] decompress(byte[] compressed, int expectedLength) {
            if (compressed.length != expectedLength) {
                throw new FsException(FsErrorCode.INTERNAL, "stored length mismatch");
            }
            return compressed.clone();
        }
    };

    /** Cheap to decode and encode: for hot (frequently read) nodes. */
    public static final Codec DEFLATE_FAST = new DeflateCodec(DEFLATE_FAST_ID, "deflate-fast", Deflater.BEST_SPEED);

    /** Smallest output the JDK can produce: for cold nodes. Plug a stronger codec (zstd, xz) with {@link #register}. */
    public static final Codec DEFLATE_MAX = new DeflateCodec(DEFLATE_MAX_ID, "deflate-max", Deflater.BEST_COMPRESSION);

    private static final Map<Integer, Codec> REGISTRY = new ConcurrentHashMap<>();

    static {
        register(NONE);
        register(DEFLATE_FAST);
        register(DEFLATE_MAX);
    }

    private Codecs() {
    }

    /** Registers an additional codec. Ids are permanent: registering an id twice is an error. */
    public static void register(Codec codec) {
        if (codec.id() < 0 || codec.id() > 255) {
            throw new IllegalArgumentException("codec id must be 0..255");
        }
        Codec previous = REGISTRY.putIfAbsent(codec.id(), codec);
        if (previous != null && previous != codec) {
            throw new IllegalStateException("codec id " + codec.id() + " already registered by " + previous.name());
        }
    }

    public static Codec byId(int id) {
        Codec c = REGISTRY.get(id);
        if (c == null) {
            throw new FsException(FsErrorCode.INTERNAL, "unknown codec id " + id);
        }
        return c;
    }

    private static final class DeflateCodec implements Codec {
        private final int id;
        private final String name;
        private final int level;

        DeflateCodec(int id, String name, int level) {
            this.id = id;
            this.name = name;
            this.level = level;
        }

        @Override public int id() { return id; }
        @Override public String name() { return name; }

        @Override
        public byte[] compress(byte[] plain) {
            Deflater d = new Deflater(level, true);
            try {
                d.setInput(plain);
                d.finish();
                byte[] buf = new byte[Math.max(64, plain.length + plain.length / 8 + 64)];
                int n = 0;
                while (!d.finished()) {
                    if (n == buf.length) {
                        buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                    }
                    n += d.deflate(buf, n, buf.length - n);
                }
                return java.util.Arrays.copyOf(buf, n);
            } finally {
                d.end();
            }
        }

        @Override
        public byte[] decompress(byte[] compressed, int expectedLength) {
            Inflater inf = new Inflater(true);
            try {
                inf.setInput(compressed);
                byte[] out = new byte[expectedLength];
                int n = 0;
                while (n < expectedLength) {
                    int r = inf.inflate(out, n, expectedLength - n);
                    if (r == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) {
                        break;
                    }
                    n += r;
                }
                if (n != expectedLength) {
                    throw new FsException(FsErrorCode.DIGEST_MISMATCH, "decompressed length mismatch");
                }
                // anything left would mean more output than announced
                byte[] probe = new byte[1];
                if (inf.inflate(probe) != 0) {
                    throw new FsException(FsErrorCode.DIGEST_MISMATCH, "decompressed data longer than announced");
                }
                return out;
            } catch (DataFormatException e) {
                throw new FsException(FsErrorCode.DIGEST_MISMATCH, "corrupt compressed data", e);
            } finally {
                inf.end();
            }
        }
    }
}
