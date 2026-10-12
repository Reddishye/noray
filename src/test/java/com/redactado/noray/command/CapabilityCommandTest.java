package com.redactado.noray.command;

import com.redactado.noray.command.builtin.CapabilityCommand;
import com.redactado.noray.kernel.api.*;
import com.redactado.noray.kernel.capability.CapabilityRegistry;
import com.redactado.noray.kernel.capability.CapabilitySet;
import com.redactado.noray.kernel.core.DefaultServiceRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityCommandTest {

    private String run(DefaultServiceRegistry services, int[] code, String... args) {
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
        CommandInvocation inv = CommandInvocation.builder().rawCommandLine("capability").commandName("capability")
                .args(List.of(args)).context(ctx).out(ps).err(ps).build();
        code[0] = new CapabilityCommand().execute(inv);
        ps.flush();
        return buf.toString();
    }

    @Test
    void listShowAuditAndCheck() {
        int[] code = new int[1];
        DefaultServiceRegistry services = new DefaultServiceRegistry();
        CapabilityRegistry reg = new CapabilityRegistry();
        services.register(CapabilityRegistry.class, reg);
        reg.setGrants("repo", CapabilitySet.parse("fs.write:/mnt/repo, event.publish:*"));
        reg.setGrants("web", CapabilitySet.NONE);
        reg.deny("web", "service:Keyring");

        String list = run(services, code, "list");
        assertEquals(0, code[0]);
        assertTrue(list.contains("repo: fs.write:/mnt/repo, event.publish:*"), list);
        assertTrue(list.contains("web: (nothing)"), list);

        assertTrue(run(services, code, "show", "repo").contains("fs.write:/mnt/repo"));
        run(services, code, "show", "nope");
        assertEquals(1, code[0]);

        String audit = run(services, code, "audit");
        assertTrue(audit.contains("Refusals so far: 1") && audit.contains("web  service:Keyring"), audit);

        assertTrue(run(services, code, "check", "service:*", "executor").startsWith("OK"));
        assertEquals(0, code[0]);
        run(services, code, "check", "fs.read:relative");
        assertEquals(1, code[0]);
    }
}
