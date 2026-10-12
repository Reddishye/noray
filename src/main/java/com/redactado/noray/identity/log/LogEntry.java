package com.redactado.noray.identity.log;

import com.redactado.noray.identity.SigningKey;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** One signed link of a {@link SignedLog}: points to the previous entry by hash and is signed by its author. */
public final class LogEntry {

    public enum Type {
        /** First entry: declares the log and its owner (the signer). */
        GENESIS,
        /** Application data. Any current writer may append. */
        APPEND,
        /** The owner admits another writer (payload = 32-byte public key). */
        GRANT,
        /** The owner removes a writer (payload = 32-byte public key). */
        REVOKE
    }

    static final String DOMAIN = "noray/log-entry/v1";
    static final int MAX_PAYLOAD = 1 << 20;
    static final byte[] ZERO_HASH = new byte[32];

    private final long seq;
    private final byte[] prev;
    private final long timeMillis;
    private final Type type;
    private final byte[] payload;
    private final byte[] signer;
    private final byte[] signature;

    LogEntry(long seq, byte[] prev, long timeMillis, Type type, byte[] payload, byte[] signer, byte[] signature) {
        if (prev.length != 32 || signer.length != 32 || signature.length != 64) {
            throw new IllegalArgumentException("bad entry field size");
        }
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload too large");
        }
        this.seq = seq;
        this.prev = prev.clone();
        this.timeMillis = timeMillis;
        this.type = type;
        this.payload = payload.clone();
        this.signer = signer.clone();
        this.signature = signature.clone();
    }

    static LogEntry create(SigningKey key, long seq, byte[] prev, long timeMillis, Type type, byte[] payload) {
        byte[] body = body(seq, prev, timeMillis, type, payload, key.publicKey());
        return new LogEntry(seq, prev, timeMillis, type, payload, key.publicKey(), key.sign(DOMAIN, body));
    }

    static byte[] body(long seq, byte[] prev, long timeMillis, Type type, byte[] payload, byte[] signer) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buf);
            out.writeLong(seq);
            out.write(prev);
            out.writeLong(timeMillis);
            out.writeByte(type.ordinal());
            out.writeInt(payload.length);
            out.write(payload);
            out.write(signer);
            return buf.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    byte[] body() {
        return body(seq, prev, timeMillis, type, payload, signer);
    }

    public boolean signatureValid() {
        return SigningKey.verify(signer, DOMAIN, body(), signature);
    }

    public long seq() { return seq; }
    public byte[] prev() { return prev.clone(); }
    public long timeMillis() { return timeMillis; }
    public Type type() { return type; }
    public byte[] payload() { return payload.clone(); }
    public byte[] signer() { return signer.clone(); }
    byte[] signatureBytes() { return signature.clone(); }

    /** Identifies this entry including its signature; the next entry commits to it. */
    public byte[] hash() {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(body());
            d.update(signature);
            return d.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return "LogEntry[" + seq + " " + type + " " + HexFormat.of().formatHex(hash(), 0, 4) + "]";
    }
}
