package com.redactado.noray.sandbox;

import com.redactado.noray.filesystem.api.DirectoryEntry;
import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.api.NodeType;
import com.redactado.noray.filesystem.store.ObjectId;
import com.redactado.noray.filesystem.vfs.VfsPath;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The {@link FileSystemService} a sandboxed program sees: every call is a request to the node. */
final class RemoteFileSystem implements FileSystemService {

    private final DataInputStream in;
    private final DataOutputStream out;

    RemoteFileSystem(DataInputStream in, DataOutputStream out) {
        this.in = in;
        this.out = out;
    }

    private interface Body {
        void write(DataOutputStream o) throws IOException;
    }

    private synchronized DataInputStream call(byte op, Body body) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bytes);
            o.writeByte(op);
            body.write(o);
            Rpc.writeFrame(out, bytes.toByteArray());
            DataInputStream reply = Rpc.readFrame(in);
            byte status = reply.readByte();
            if (status == Rpc.OK) {
                return reply;
            }
            if (status == Rpc.FS_ERROR) {
                FsErrorCode code;
                try {
                    code = FsErrorCode.valueOf(Rpc.readString(reply));
                } catch (IllegalArgumentException e) {
                    code = FsErrorCode.INTERNAL;
                }
                throw new FsException(code, Rpc.readString(reply));
            }
            throw new FsException(FsErrorCode.INTERNAL, "bridge refused the request: " + Rpc.readString(reply));
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "lost the connection to the node", e);
        }
    }

    @Override public void mkdir(VfsPath path) { call(Rpc.MKDIR, o -> Rpc.writeString(o, path.toString())); }
    @Override public void createFile(VfsPath path) { call(Rpc.CREATE, o -> Rpc.writeString(o, path.toString())); }
    @Override public void unlink(VfsPath path) { call(Rpc.UNLINK, o -> Rpc.writeString(o, path.toString())); }

    @Override
    public DirectoryEntry stat(VfsPath path) {
        try {
            DataInputStream r = call(Rpc.STAT, o -> Rpc.writeString(o, path.toString()));
            return new DirectoryEntry(Rpc.readString(r), NodeType.valueOf(Rpc.readString(r)));
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "bad reply", e);
        }
    }

    @Override
    public List<DirectoryEntry> readdir(VfsPath path) {
        try {
            DataInputStream r = call(Rpc.READDIR, o -> Rpc.writeString(o, path.toString()));
            int n = r.readInt();
            List<DirectoryEntry> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                out.add(new DirectoryEntry(Rpc.readString(r), NodeType.valueOf(Rpc.readString(r))));
            }
            return out;
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "bad reply", e);
        }
    }

    @Override
    public ObjectId writeFully(VfsPath path, byte[] data) {
        try {
            DataInputStream r = call(Rpc.WRITE, o -> {
                Rpc.writeString(o, path.toString());
                Rpc.writeBytes(o, data);
            });
            return new ObjectId(Rpc.readString(r));
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "bad reply", e);
        }
    }

    @Override
    public byte[] readFully(VfsPath path) {
        try {
            DataInputStream r = call(Rpc.READ, o -> Rpc.writeString(o, path.toString()));
            return Rpc.readBytes(r, Rpc.MAX_FRAME);
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "bad reply", e);
        }
    }

    @Override
    public boolean hasContent(VfsPath path) {
        try {
            return call(Rpc.HAS_CONTENT, o -> Rpc.writeString(o, path.toString())).readBoolean();
        } catch (IOException e) {
            throw new FsException(FsErrorCode.INTERNAL, "bad reply", e);
        }
    }
}
