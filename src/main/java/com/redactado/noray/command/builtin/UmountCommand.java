package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.filesystem.vfs.VirtualFileSystem;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.kernel.api.KernelContext;

import java.util.List;

public final class UmountCommand implements Command {

    @Override public String name() { return "umount"; }
    @Override public String description() { return "Unmount a filesystem"; }
    @Override public List<String> aliases() { return List.of("unmount"); }
    @Override public String synopsis() { return "umount <PATH>"; }
    @Override public CommandCategory category() { return CommandCategory.SYSTEM; }

    @Override
    public int execute(CommandInvocation invocation) throws Exception {
        KernelContext ctx = invocation.context();
        VirtualFileSystem vfs = ctx.getServiceRegistry().get(VirtualFileSystem.class).orElse(null);
        var out = invocation.out();
        var args = invocation.args();

        if (args.isEmpty()) {
            out.println("Usage: umount <PATH>");
            return 1;
        }

        try {
            vfs.unmount(VfsPath.parse(args.get(0)));
            return 0;
        } catch (Exception e) {
            out.println("umount: " + e.getMessage());
            return 1;
        }
    }
}