package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.filesystem.volume.VolumeManager;
import com.redactado.noray.kernel.api.KernelContext;

import java.util.List;

public final class LsblkCommand implements Command {

    @Override public String name() { return "lsblk"; }
    @Override public String description() { return "List block devices (volumes)"; }
    @Override public List<String> aliases() { return List.of("volumes"); }
    @Override public String synopsis() { return "lsblk"; }
    @Override public CommandCategory category() { return CommandCategory.SYSTEM; }

    @Override
    public int execute(CommandInvocation invocation) throws Exception {
        KernelContext ctx = invocation.context();
        VolumeManager vm = ctx.getServiceRegistry().get(VolumeManager.class).orElse(null);
        if (vm == null) {
            invocation.out().println("No volume manager");
            return 1;
        }
        var out = invocation.out();
        out.printf("%-36s %-10s %-10s %s%n", "UUID", "KIND", "STATE", "WRITABLE");
        for (Volume v : vm.list()) {
            out.printf("%-36s %-10s %-10s %s%n",
                    v.uuid(), v.kind(), v.state(), v.writable());
        }
        return 0;
    }
}