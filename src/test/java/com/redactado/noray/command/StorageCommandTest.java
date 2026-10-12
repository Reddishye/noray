package com.redactado.noray.command;

import com.redactado.noray.command.builtin.StorageCommand;
import com.redactado.noray.data.GcReport;
import com.redactado.noray.data.placement.DataCluster;
import com.redactado.noray.data.placement.SimpleNode;
import com.redactado.noray.data.tier.PolicyCatalog;
import com.redactado.noray.filesystem.api.StorageAdmin;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.kernel.api.*;
import com.redactado.noray.kernel.core.DefaultServiceRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class StorageCommandTest {

    private final List<String> calls = new ArrayList<>();

    private final StorageAdmin admin = new StorageAdmin() {
        @Override public void setPolicy(VfsPath dir, String policy) { calls.add("set " + dir + " [" + policy + "]"); }
        @Override public String policyOf(VfsPath path) { return "tiered"; }
        @Override public int applyPolicy(VfsPath dir) { calls.add("apply " + dir); return 3; }
        @Override public String storageClassOf(VfsPath file) { return "archive"; }
        @Override public RepairSummary repair(boolean scrub, boolean now) {
            calls.add("repair scrub=" + scrub + " now=" + now);
            return new RepairSummary(5, 2, 0, 7, 1, 2, 0);
        }
        @Override public GcReport gc(boolean dryRun) {
            calls.add("gc dry=" + dryRun);
            return new GcReport(3, 40, 4, 1000, 2, List.of(), dryRun);
        }
        @Override public TierSummary autoTier() { return new TierSummary(9, 2, 1, 0); }
        @Override public void observeNodes() { calls.add("observe"); }
    };

    private String run(int[] code, String... args) throws Exception {
        DefaultServiceRegistry services = new DefaultServiceRegistry();
        services.register(StorageAdmin.class, admin);
        services.register(PolicyCatalog.class, PolicyCatalog.defaults());
        SimpleNode node = new SimpleNode("node-a", new InMemoryObjectStore());
        services.register(DataCluster.class, DataCluster.of(List.of(node)));
        KernelContext ctx = new KernelContext() {
            @Override public KernelPhase getPhase() { return null; }
            @Override public ServiceRegistry getServiceRegistry() { return services; }
            @Override public EventBus getEventBus() { return null; }
            @Override public ExecutorService getExecutionEngine() { return null; }
            @Override public Configuration getConfiguration() { return null; }
            @Override public void registerResource(String m, AutoCloseable r) { }
            @Override public List<KernelModule> getActiveModules() { return List.of(); }
            @Override public Set<String> getActiveModuleNames() { return Set.of(); }
        };
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf);
        CommandInvocation inv = CommandInvocation.builder().rawCommandLine("storage").commandName("storage")
                .args(List.of(args)).context(ctx).out(ps).err(ps).build();
        code[0] = new StorageCommand().execute(inv);
        ps.flush();
        return buf.toString();
    }

    @Test
    void repairPassesItsOptionsAndReports() throws Exception {
        int[] code = new int[1];
        String out = run(code, "repair", "--scrub", "--now");
        assertEquals(0, code[0]);
        assertTrue(out.contains("repaired: 2"), out);
        assertTrue(calls.contains("repair scrub=true now=true"));
    }

    @Test
    void gcDefaultsToARealRunAndSupportsDryRun() throws Exception {
        int[] code = new int[1];
        run(code, "gc", "--dry-run");
        run(code, "gc");
        assertEquals(List.of("gc dry=true", "gc dry=false"), calls);
    }

    @Test
    void policyAndNodeSubcommandsWork() throws Exception {
        int[] code = new int[1];
        assertTrue(run(code, "policy", "list").contains("tiered"));
        run(code, "policy", "set", "/mnt/pool/docs", "archive");
        run(code, "policy", "apply", "/mnt/pool/docs");
        assertTrue(calls.contains("set /mnt/pool/docs [archive]"));
        assertTrue(run(code, "class", "/mnt/pool/docs/a").contains("archive"));
        assertTrue(run(code, "tier").contains("demoted: 2"));
        assertTrue(run(code, "nodes").contains("node-a"));
        run(code, "bogus");
        assertEquals(1, code[0]);
    }
}
