package com.redactado.noray.sandbox;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The narrow protocol between the node and the sandboxed process: length-prefixed request/response frames. */
final class Rpc {

    static final byte MKDIR = 1;
    static final byte CREATE = 2;
    static final byte UNLINK = 3;
    static final byte STAT = 4;
    static final byte READDIR = 5;
    static final byte READ = 6;
    static final byte WRITE = 7;
    static final byte HAS_CONTENT = 8;

    static final byte OK = 0;
    static final byte FS_ERROR = 1;
    static final byte BAD_REQUEST = 2;

    /** Largest frame either side accepts: nothing the other side sends can make us allocate more. */
    static final int MAX_FRAME = 32 * 1024 * 1024;

    private Rpc() {
    }

    static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    static String readString(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 64 * 1024) {
            throw new IOException("bad string length " + n);
        }
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    static void writeBytes(DataOutputStream out, byte[] b) throws IOException {
        out.writeInt(b.length);
        out.write(b);
    }

    static byte[] readBytes(DataInputStream in, int limit) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > limit) {
            throw new IOException("bad length " + n);
        }
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    /** Reads one frame: {@code length(4) | body}. */
    static DataInputStream readFrame(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 1 || len > MAX_FRAME) {
            throw new IOException("bad frame length " + len);
        }
        byte[] body = new byte[len];
        in.readFully(body);
        return new DataInputStream(new java.io.ByteArrayInputStream(body));
    }

    static void writeFrame(DataOutputStream out, byte[] body) throws IOException {
        if (body.length > MAX_FRAME) {
            throw new IOException("frame too large");
        }
        out.writeInt(body.length);
        out.write(body);
        out.flush();
    }
}
