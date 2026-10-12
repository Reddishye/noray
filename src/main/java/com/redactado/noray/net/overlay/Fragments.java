package com.redactado.noray.net.overlay;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;

import java.nio.ByteBuffer;

/**
 * Messages larger than one datagram are cut into fragments that are acknowledged selectively and retransmitted.
 * Everything here runs <i>inside</i> the encrypted, padded frames of the overlay: the network sees only equal-looking
 * packets.
 *
 * <pre>
 *   FRAG: type(0xF0) msgId(4) index(2) count(2) channel(1) data
 *   ACK : type(0xF1) msgId(4) base(2) bitmap(32)    base = first fragment not received yet;
 *                                                   bit i of the bitmap = fragment base+i received
 * </pre>
 */
final class Fragments {

    static final int TYPE_FRAG = 0xF0;
    static final int TYPE_ACK = 0xF1;
    /** Relay frames: {@code RELAY} asks a relay to pass a datagram on, {@code RELAYED} is what it delivers. */
    static final int TYPE_RELAY = 0xF2;
    static final int TYPE_RELAYED = 0xF3;
    /** Payload bytes per fragment; also the largest message sent as a single, unacknowledged datagram. */
    static final int FRAGMENT_DATA = 1000;
    static final int BITMAP_BYTES = 32;
    static final int MAX_FRAGMENTS = 60_000;

    private Fragments() {
    }

    /** Receives the frames to put on the wire (type byte plus body, before padding and encryption). */
    @FunctionalInterface
    interface Transmitter {
        void transmit(int type, byte[] body);
    }

    record Frag(int msgId, int index, int count, int channel, byte[] data) {
    }

    record Ack(int msgId, int base, byte[] bitmap) {
        boolean has(int index) {
            int i = index - base;
            return i >= 0 && i < BITMAP_BYTES * 8 && (bitmap[i >>> 3] & (1 << (i & 7))) != 0;
        }
    }

    static byte[] encodeFrag(int msgId, int index, int count, int channel, byte[] src, int off, int len) {
        ByteBuffer b = ByteBuffer.allocate(4 + 2 + 2 + 1 + len);
        b.putInt(msgId).putShort((short) index).putShort((short) count).put((byte) channel).put(src, off, len);
        return b.array();
    }

    /** @param frame the unpadded frame, type byte first */
    static Frag parseFrag(byte[] frame) {
        if (frame.length < 1 + 9 || frame.length > 1 + 9 + FRAGMENT_DATA) {
            throw new IllegalArgumentException("bad fragment size");
        }
        ByteBuffer b = ByteBuffer.wrap(frame, 1, frame.length - 1);
        int msgId = b.getInt();
        int index = b.getShort() & 0xFFFF;
        int count = b.getShort() & 0xFFFF;
        int channel = b.get() & 0xFF;
        byte[] data = new byte[b.remaining()];
        b.get(data);
        if (count < 1 || count > MAX_FRAGMENTS || index >= count) {
            throw new IllegalArgumentException("bad fragment numbering");
        }
        return new Frag(msgId, index, count, channel, data);
    }

    static byte[] encodeAck(int msgId, int base, byte[] bitmap) {
        return ByteBuffer.allocate(4 + 2 + BITMAP_BYTES).putInt(msgId).putShort((short) base).put(bitmap).array();
    }

    static Ack parseAck(byte[] frame) {
        if (frame.length != 1 + 4 + 2 + BITMAP_BYTES) {
            throw new IllegalArgumentException("bad ack size");
        }
        ByteBuffer b = ByteBuffer.wrap(frame, 1, frame.length - 1);
        int msgId = b.getInt();
        int base = b.getShort() & 0xFFFF;
        byte[] bitmap = new byte[BITMAP_BYTES];
        b.get(bitmap);
        return new Ack(msgId, base, bitmap);
    }

    static int fragmentCount(int payloadLength) {
        return (payloadLength + FRAGMENT_DATA - 1) / FRAGMENT_DATA;
    }

    // ── sender ─────────────────────────────────────────────────────────────────

    /**
     * One message on its way out. Sliding window with a window that grows with every acknowledged fragment and halves
     * on a timeout, and a retransmission timeout that follows the measured round trip.
     */
    static final class Outgoing {
        private static final int MAX_WINDOW = 256;
        private static final long MIN_RTO = 60;
        private static final long MAX_RTO = 3_000;

        final int msgId;
        private final int channel;
        private final byte[] payload;
        private final int count;
        private final boolean[] acked;
        private final boolean[] retransmitted;
        private final long[] sentAt;
        private int ackedCount;
        private int window = 16;
        private long srtt;
        private long rto = 300;
        private int lowestUnacked;

        Outgoing(int msgId, int channel, byte[] payload) {
            this.msgId = msgId;
            this.channel = channel;
            this.payload = payload;
            this.count = fragmentCount(payload.length);
            if (count > MAX_FRAGMENTS) {
                throw new FsException(FsErrorCode.INVALID_PATH, "message too large for the overlay");
            }
            this.acked = new boolean[count];
            this.retransmitted = new boolean[count];
            this.sentAt = new long[count];
        }

        synchronized void onAck(Ack a) {
            long now = System.nanoTime() / 1_000_000;
            int fresh = 0;
            for (int i = 0; i < Math.min(a.base(), count); i++) {
                fresh += mark(i, now);
            }
            for (int i = a.base(); i < Math.min(count, a.base() + BITMAP_BYTES * 8); i++) {
                if (a.has(i)) {
                    fresh += mark(i, now);
                }
            }
            if (fresh > 0) {
                window = Math.min(MAX_WINDOW, window + fresh);
                notifyAll();
            }
        }

        private int mark(int i, long now) {
            if (acked[i]) {
                return 0;
            }
            acked[i] = true;
            ackedCount++;
            if (sentAt[i] != 0 && !retransmitted[i]) { // only unambiguous samples (Karn)
                long sample = Math.max(1, now - sentAt[i]);
                srtt = srtt == 0 ? sample : (srtt * 7 + sample) / 8;
                rto = Math.max(MIN_RTO, Math.min(MAX_RTO, 2 * srtt + 30));
            }
            return 1;
        }

        /** Blocks until every fragment is acknowledged. @throws FsException VOLUME_UNAVAILABLE after {@code timeoutMillis} */
        void run(Transmitter tx, long timeoutMillis) throws InterruptedException {
            long deadline = System.nanoTime() / 1_000_000 + timeoutMillis;
            int next = 0;
            synchronized (this) {
                while (ackedCount < count) {
                    long now = System.nanoTime() / 1_000_000;
                    if (now > deadline) {
                        throw new FsException(FsErrorCode.VOLUME_UNAVAILABLE,
                                "peer did not acknowledge message " + msgId + " (" + ackedCount + "/" + count + ")");
                    }
                    while (lowestUnacked < count && acked[lowestUnacked]) {
                        lowestUnacked++;
                    }
                    boolean lost = false;
                    for (int i = lowestUnacked; i < next; i++) { // retransmit what timed out
                        if (!acked[i] && now - sentAt[i] >= rto) {
                            retransmitted[i] = true;
                            sentAt[i] = now;
                            send(tx, i);
                            lost = true;
                        }
                    }
                    if (lost) {
                        window = Math.max(4, window / 2);
                        rto = Math.min(MAX_RTO, rto * 2);
                    }
                    int inFlight = 0;
                    for (int i = lowestUnacked; i < next; i++) {
                        if (!acked[i]) {
                            inFlight++;
                        }
                    }
                    while (next < count && inFlight < window) {
                        sentAt[next] = now;
                        send(tx, next++);
                        inFlight++;
                    }
                    if (ackedCount < count) {
                        wait(Math.max(5, Math.min(rto / 2, 50)));
                    }
                }
            }
        }

        private void send(Transmitter tx, int i) {
            int off = i * FRAGMENT_DATA;
            int len = Math.min(FRAGMENT_DATA, payload.length - off);
            tx.transmit(TYPE_FRAG, encodeFrag(msgId, i, count, channel, payload, off, len));
        }
    }

    // ── receiver ───────────────────────────────────────────────────────────────

    /** A message being put together. */
    static final class Incoming {
        final int count;
        final int channel;
        final long created = System.nanoTime();
        private final byte[][] parts;
        private int received;

        Incoming(int count, int channel) {
            this.count = count;
            this.channel = channel;
            this.parts = new byte[count][];
        }

        boolean compatible(Frag f) {
            return f.count() == count && f.channel() == channel;
        }

        /** @return true if the fragment was new */
        synchronized boolean accept(Frag f) {
            if (parts[f.index()] != null) {
                return false;
            }
            parts[f.index()] = f.data();
            received++;
            return true;
        }

        synchronized int received() {
            return received;
        }

        synchronized boolean complete() {
            return received == count;
        }

        synchronized int base() {
            int i = 0;
            while (i < count && parts[i] != null) {
                i++;
            }
            return i;
        }

        synchronized byte[] bitmapFrom(int base) {
            byte[] bm = new byte[BITMAP_BYTES];
            for (int i = 0; i < BITMAP_BYTES * 8 && base + i < count; i++) {
                if (parts[base + i] != null) {
                    bm[i >>> 3] |= (byte) (1 << (i & 7));
                }
            }
            return bm;
        }

        synchronized byte[] assemble() {
            int total = 0;
            for (byte[] p : parts) {
                total += p.length;
            }
            byte[] out = new byte[total];
            int off = 0;
            for (byte[] p : parts) {
                System.arraycopy(p, 0, out, off, p.length);
                off += p.length;
            }
            return out;
        }
    }
}
