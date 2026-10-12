package com.redactado.noray.pkg;

import com.redactado.noray.pkg.PackageException.Reason;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The {@code .nrpkg} file: one manifest and one payload, nothing else, so there is no archive format to confuse
 * (no paths, no duplicate entries, no compression bombs).
 *
 * <pre>"NRPKG1\n" | u32 manifest length | manifest | u64 payload length | payload</pre>
 *
 * Lengths are checked against the limits before anything is allocated, and trailing bytes are refused.
 */
public record PackageArchive(byte[] manifest, byte[] payload) {

    private static final byte[] MAGIC = "NRPKG1\n".getBytes(StandardCharsets.US_ASCII);

    public PackageArchive {
        manifest = manifest.clone();
        payload = payload.clone();
    }

    public static PackageArchive read(InputStream raw) {
        try {
            DataInputStream in = new DataInputStream(raw);
            byte[] magic = in.readNBytes(MAGIC.length);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new PackageException(Reason.MALFORMED, "not a noray package (bad magic)");
            }
            long mLen = Integer.toUnsignedLong(in.readInt());
            if (mLen > PackageManifest.MAX_BYTES) {
                throw new PackageException(Reason.MALFORMED, "manifest larger than " + PackageManifest.MAX_BYTES);
            }
            byte[] manifest = in.readNBytes((int) mLen);
            long pLen = in.readLong();
            if (pLen < 0 || pLen > PackageManifest.MAX_PAYLOAD) {
                throw new PackageException(Reason.MALFORMED, "payload length out of range");
            }
            byte[] payload = in.readNBytes((int) pLen);
            if (manifest.length != mLen || payload.length != pLen) {
                throw new PackageException(Reason.MALFORMED, "truncated package");
            }
            if (in.read() != -1) {
                throw new PackageException(Reason.MALFORMED, "trailing bytes after the payload");
            }
            return new PackageArchive(manifest, payload);
        } catch (IOException e) {
            throw new PackageException(Reason.MALFORMED, "truncated package", e);
        }
    }

    public static PackageArchive read(byte[] bytes) {
        return read(new ByteArrayInputStream(bytes));
    }

    public void write(OutputStream raw) throws IOException {
        DataOutputStream out = new DataOutputStream(raw);
        out.write(MAGIC);
        out.writeInt(manifest.length);
        out.write(manifest);
        out.writeLong(payload.length);
        out.write(payload);
        out.flush();
    }

    public byte[] toBytes() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(manifest.length + payload.length + 32);
        try {
            write(bos);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }

    @Override
    public byte[] manifest() {
        return manifest.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
