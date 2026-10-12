package com.redactado.noray.kernel.core;

import com.redactado.noray.kernel.api.InitLevel;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.kernel.api.KernelModule;
import com.redactado.noray.kernel.exception.KernelException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DependencyResolverTest {

    record TestModule(String id, Set<String> dependencies, InitLevel getInitLevel, int getOrderWithinLevel, boolean isCritical) implements KernelModule {
        @Override public void init(KernelContext context) {}
        @Override public void shutdown(KernelContext context) {}
    }

    @Test
    void testValidTopologicalResolutionWithLevels() {
        TestModule core = new TestModule("core", Set.of(), InitLevel.CORE, 0, true);
        TestModule db = new TestModule("db", Set.of("core"), InitLevel.SUBSYSTEM, 0, true);
        TestModule cache = new TestModule("cache", Set.of("core"), InitLevel.SUBSYSTEM, 10, false);
        TestModule app = new TestModule("app", Set.of("db", "cache"), InitLevel.APPLICATION, 0, false);

        List<KernelModule> resolved = DependencyResolver.resolveExecutionOrder(List.of(app, cache, db, core));

        assertEquals(4, resolved.size());
        assertEquals("core", resolved.get(0).id());
        assertEquals("db", resolved.get(1).id());
        assertEquals("cache", resolved.get(2).id());
        assertEquals("app", resolved.get(3).id());
    }

    @Test
    void testCircularDependencyFails() {
        TestModule m1 = new TestModule("m1", Set.of("m2"), InitLevel.SUBSYSTEM, 0, false);
        TestModule m2 = new TestModule("m2", Set.of("m1"), InitLevel.SUBSYSTEM, 0, false);

        assertThrows(KernelException.class, () -> DependencyResolver.resolveExecutionOrder(List.of(m1, m2)));
    }

    @Test
    void testDependencyInversionFails() {
        TestModule core = new TestModule("core", Set.of("app"), InitLevel.CORE, 0, true);
        TestModule app = new TestModule("app", Set.of(), InitLevel.APPLICATION, 0, false);

        assertThrows(KernelException.class, () -> DependencyResolver.resolveExecutionOrder(List.of(core, app)));
    }
}
