package com.redactado.noray.filesystem.vfs.journal;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** A whole list of namespace records as one byte string (for snapshots that leave the journal file). */
public final class NamespaceRecordCodec {

    private NamespaceRecordCodec() {
    }

    public static byte[] encode(List<NamespaceRecord> records) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeInt(records.size());
            for (NamespaceRecord r : records) {
                byte[] b = FileNamespaceJournal.encode(r);
                out.writeInt(b.length);
                out.write(b);
            }
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Malformed input fails with {@link IllegalArgumentException} and nothing else (fuzz-tested). */
    public static List<NamespaceRecord> decode(byte[] bytes) {
        try {
            return decodeAll(bytes);
        } catch (java.nio.BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated namespace snapshot", e);
        }
    }

    private static List<NamespaceRecord> decodeAll(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        int n = b.getInt();
        if (n < 0 || n > bytes.length) {
            throw new IllegalArgumentException("bad record count");
        }
        List<NamespaceRecord> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int len = b.getInt();
            if (len < 0 || len > b.remaining()) {
                throw new IllegalArgumentException("bad record length");
            }
            ByteBuffer one = ByteBuffer.wrap(bytes, b.position(), len).slice();
            out.add(FileNamespaceJournal.decode(one));
            b.position(b.position() + len);
        }
        return out;
    }
}
