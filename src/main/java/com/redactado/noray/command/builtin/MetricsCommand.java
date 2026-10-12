package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.kernel.metrics.Metrics;

import java.util.Set;

public final class MetricsCommand implements Command {

    @Override public Set<String> switchFlags() { return Set.of("json", "prometheus"); }

    @Override public String name() { return "metrics"; }
    @Override public String description() { return "Counters and gauges of this node"; }
    @Override public String synopsis() { return "metrics [--json|--prometheus] [PREFIX]"; }
    @Override public CommandCategory category() { return CommandCategory.SYSTEM; }

    @Override
    public int execute(CommandInvocation inv) {
        Metrics m = Metrics.global();
        if (inv.booleanFlags().contains("json")) {
            inv.out().println(m.json());
            return 0;
        }
        if (inv.booleanFlags().contains("prometheus")) {
            inv.out().print(m.prometheus());
            return 0;
        }
        String prefix = inv.args().isEmpty() ? "" : inv.args().get(0);
        m.snapshot().forEach((k, v) -> {
            if (k.startsWith(prefix)) inv.out().printf("%-40s %d%n", k, v);
        });
        return 0;
    }
}
