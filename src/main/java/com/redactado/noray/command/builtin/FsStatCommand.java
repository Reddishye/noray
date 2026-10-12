package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.filesystem.api.DirectoryEntry;
import com.redactado.noray.filesystem.api.FileSystemService;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.kernel.api.KernelContext;

import java.util.List;

public final class FsStatCommand implements Command {

    @Override public String name() { return "fs"; }
    @Override public String description() { return "Filesystem metadata operations"; }
    @Override public String synopsis() { return "fs stat <PATH>"; }
    @Override public CommandCategory category() { return CommandCategory.FILESYSTEM; }

    @Override
    public int execute(CommandInvocation invocation) throws Exception {
        KernelContext ctx = invocation.context();
        FileSystemService fs = ctx.getServiceRegistry().get(FileSystemService.class).orElse(null);
        var out = invocation.out();
        var args = invocation.args();

        if (args.isEmpty() || !args.get(0).equals("stat")) {
            out.println("Usage: fs stat <PATH>");
            return 1;
        }

        if (args.size() < 2) {
            out.println("Usage: fs stat <PATH>");
            return 1;
        }

        try {
            DirectoryEntry entry = fs.stat(VfsPath.parse(args.get(1)));
            out.printf("name: %s%n", entry.name());
            out.printf("type: %s%n", entry.type());
            return 0;
        } catch (Exception e) {
            out.println("fs: " + e.getMessage());
            return 1;
        }
    }
}