package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.filesystem.volume.VolumeManager;
import com.redactado.noray.kernel.api.Configuration;
import com.redactado.noray.kernel.api.KernelContext;

import java.util.List;

public final class DfCommand implements Command {

    @Override public String name() { return "df"; }
    @Override public String description() { return "Report filesystem disk usage"; }
    @Override public String synopsis() { return "df"; }
    @Override public CommandCategory category() { return CommandCategory.SYSTEM; }

    @Override
    public int execute(CommandInvocation invocation) throws Exception {
        KernelContext ctx = invocation.context();
        VolumeManager vm = ctx.getServiceRegistry().get(VolumeManager.class).orElse(null);
        Configuration cfg = ctx.getConfiguration();
        var out = invocation.out();

        int count = vm != null ? vm.list().size() : 0;
        out.printf("%-10s %-10s%n", "Volumes:", count);
        out.printf("%-10s %-10s%n", "replica.n:", cfg.get("noray.fs.replica.n", "3"));
        out.printf("%-10s %-10s%n", "replica.w:", cfg.get("noray.fs.replica.w", "2"));
        out.printf("%-10s %-10s%n", "replica.r:", cfg.get("noray.fs.replica.r", "2"));
        return 0;
    }
}