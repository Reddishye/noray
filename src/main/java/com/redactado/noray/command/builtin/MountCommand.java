package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.filesystem.vfs.Mount;
import com.redactado.noray.filesystem.vfs.VirtualFileSystem;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.filesystem.volume.VolumeId;
import com.redactado.noray.kernel.api.KernelContext;

import java.util.List;
import java.util.UUID;

public final class MountCommand implements Command {

    @Override public String name() { return "mount"; }
    @Override public String description() { return "Show or mount filesystems"; }
    @Override public String synopsis() { return "mount [UUID PATH]"; }
    @Override public CommandCategory category() { return CommandCategory.SYSTEM; }

    @Override
    public int execute(CommandInvocation invocation) throws Exception {
        KernelContext ctx = invocation.context();
        VirtualFileSystem vfs = ctx.getServiceRegistry().get(VirtualFileSystem.class).orElse(null);
        var out = invocation.out();
        var args = invocation.args();

        if (args.isEmpty()) {
            for (Mount m : vfs.mounts()) {
                out.printf("%s on %s type %s%n", m.volumeId(), m.mountPoint(), m.name());
            }
            return 0;
        }

        if (args.size() < 2) {
            out.println("Usage: mount [UUID] [PATH]");
            return 1;
        }

        try {
            UUID uuid = UUID.fromString(args.get(0));
            String path = args.get(1);
            vfs.mount(new Mount("noray-" + uuid.toString(), VfsPath.parse(path), new VolumeId(uuid), ""));
            return 0;
        } catch (Exception e) {
            out.println("mount: " + e.getMessage());
            return 1;
        }
    }
}