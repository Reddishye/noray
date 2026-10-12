package com.redactado.noray.net.onion;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Splits messages into cell-sized chunks and puts them back together, with hard limits on what is kept. */
final class Reassembler {

    /** msgId(8) | index(2) | total(2) */
    static final int HEADER = 12;
    private static final int MAX_PARTIAL = 256;

    private static final class Partial {
        final byte[][] chunks;
        int received;
        int bytes;
        final long firstSeen;

        Partial(int total, long now) {
            chunks = new byte[total][];
            firstSeen = now;
        }
    }

    private final int maxMessage;
    private final long ttlMillis;
    private final Map<Long, Partial> partial = new LinkedHashMap<>();

    Reassembler(int maxMessage, long ttlMillis) {
        this.maxMessage = maxMessage;
        this.ttlMillis = ttlMillis;
    }

    static List<byte[]> split(long messageId, byte[] message, int chunkSize) {
        int total = Math.max(1, (message.length + chunkSize - 1) / chunkSize);
        if (total > 0xffff) {
            throw new OnionException("message too large");
        }
        List<byte[]> out = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int from = i * chunkSize;
            int to = Math.min(message.length, from + chunkSize);
            ByteBuffer b = ByteBuffer.allocate(HEADER + (to - from));
            b.putLong(messageId).putShort((short) i).putShort((short) total).put(message, from, to - from);
            out.add(b.array());
        }
        return out;
    }

    /** @return the complete message when this chunk finishes it, otherwise {@code null} */
    synchronized byte[] accept(byte[] chunk, long now) {
        expire(now);
        if (chunk.length < HEADER) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(chunk);
        long id = b.getLong();
        int index = b.getShort() & 0xffff;
        int total = b.getShort() & 0xffff;
        if (total == 0 || index >= total || total > maxMessage / 256 + 2) {
            return null;
        }
        int size = chunk.length - HEADER;
        if (total == 1) {
            return size <= maxMessage ? Arrays.copyOfRange(chunk, HEADER, chunk.length) : null;
        }
        Partial p = partial.get(id);
        if (p == null) {
            if (partial.size() >= MAX_PARTIAL) {
                Iterator<Long> it = partial.keySet().iterator();
                it.next();
                it.remove(); // oldest incomplete message makes room
            }
            p = new Partial(total, now);
            partial.put(id, p);
        }
        if (p.chunks.length != total || p.chunks[index] != null) {
            return null; // inconsistent or duplicate chunk
        }
        if (p.bytes + size > maxMessage) {
            partial.remove(id);
            return null;
        }
        p.chunks[index] = Arrays.copyOfRange(chunk, HEADER, chunk.length);
        p.bytes += size;
        p.received++;
        if (p.received < total) {
            return null;
        }
        partial.remove(id);
        byte[] whole = new byte[p.bytes];
        int off = 0;
        for (byte[] c : p.chunks) {
            System.arraycopy(c, 0, whole, off, c.length);
            off += c.length;
        }
        return whole;
    }

    private void expire(long now) {
        partial.values().removeIf(p -> now - p.firstSeen > ttlMillis);
    }

    synchronized int pending() {
        return partial.size();
    }
}
