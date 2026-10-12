package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.identity.witness.WitnessService;

import java.io.PrintStream;
import java.time.Instant;

/** Who vouches for this node's history, whose history this node vouches for, and what looked wrong. */
public final class WitnessCommand implements Command {

    @Override public String name() { return "witness"; }
    @Override public String description() { return "Checkpoint witnesses: rollback and fork detection"; }
    @Override public String synopsis() { return "witness status|alarms|run|clear"; }
    @Override public CommandCategory category() { return CommandCategory.SYSTEM; }

    @Override
    public String longHelp() {
        return """
            This node signs a checkpoint of its namespace journal (record count, newest-record hash) and hands it
            to a few peers. If the data directory is later restored from an older copy, those peers still hold the
            newer checkpoints and the node raises an alarm. In return this node keeps checkpoints for its peers.

              witness status    checkpoints made, peers that acknowledged them, peers whose logs we hold, alarms
              witness alarms    every alarm with its time
              witness run       checkpoint and exchange right now
              witness clear     forget alarms once you have looked at them

            An alarm is evidence, not proof: restoring a backup on purpose raises one too.
            Configuration: noray.witness.enabled, noray.witness.max (witnesses, default 5), noray.witness.interval.s (300).
            """;
    }

    @Override
    public int execute(CommandInvocation inv) {
        PrintStream out = inv.out();
        WitnessService w = inv.context().getServiceRegistry().get(WitnessService.class).orElse(null);
        if (w == null) {
            inv.err().println("witness: not active (needs noray.identity.authority.keys and noray.witness.enabled)");
            return 1;
        }
        String action = inv.args().isEmpty() ? "status" : inv.args().get(0);
        switch (action) {
            case "status" -> {
                out.println("checkpoints made: " + w.ownCheckpoints());
                w.witnessAcknowledged().forEach((p, n) -> out.println("  witness " + p.hex() + " holds " + n + " entries of ours"));
                w.held().forEach((p, n) -> out.println("  we hold " + n + " entries of " + p.hex()));
                out.println("alarms: " + w.alarms().size());
                return w.alarms().isEmpty() ? 0 : 2;
            }
            case "alarms" -> {
                for (var a : w.alarms()) {
                    out.println(Instant.ofEpochMilli(a.timeMillis()) + "  " + a);
                }
                if (w.alarms().isEmpty()) out.println("none");
                return w.alarms().isEmpty() ? 0 : 2;
            }
            case "run" -> {
                w.runOnce();
                out.println("done");
                return 0;
            }
            case "clear" -> {
                w.clearAlarms();
                return 0;
            }
            default -> {
                inv.err().println("witness status|alarms|run|clear");
                return 1;
            }
        }
    }
}
