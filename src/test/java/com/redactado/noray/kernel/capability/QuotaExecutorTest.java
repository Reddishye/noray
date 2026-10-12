package com.redactado.noray.kernel.capability;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QuotaExecutorTest {

    @Test
    void aModuleNeverRunsMoreTasksThanItsShareAndCannotQueueWithoutBound() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            var q = new RestrictedKernelContext.QuotaExecutor(pool, 2, 3);
            CountDownLatch gate = new CountDownLatch(1);
            AtomicInteger peak = new AtomicInteger(), now = new AtomicInteger(), done = new AtomicInteger();
            Runnable task = () -> {
                int n = now.incrementAndGet();
                peak.accumulateAndGet(n, Math::max);
                try {
                    gate.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                now.decrementAndGet();
                done.incrementAndGet();
            };
            for (int i = 0; i < 5; i++) q.execute(task); // 2 running + 3 queued
            assertThrows(RejectedExecutionException.class, () -> q.execute(task), "the sixth does not fit");
            Thread.sleep(100);
            assertEquals(2, q.running());
            assertEquals(3, q.queued());
            gate.countDown();
            for (int i = 0; i < 200 && done.get() < 5; i++) Thread.sleep(10);
            assertEquals(5, done.get());
            assertEquals(2, peak.get(), "never more than two at once although the pool has sixteen threads");
            assertThrows(SecurityException.class, q::shutdown);
        } finally {
            pool.shutdownNow();
        }
    }

    @org.junit.jupiter.api.Test
    void aModuleOverItsTaskTimeBudgetIsRefusedUntilTheMinuteEnds() throws Exception {
        var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            var q = new RestrictedKernelContext.QuotaExecutor(pool, 2, 3, 1, "greedy");
            var done = new java.util.concurrent.CountDownLatch(1);
            q.execute(() -> {
                try { Thread.sleep(1_200); } catch (InterruptedException ignored) { }
                done.countDown();
            });
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
            Thread.sleep(50); // let the bookkeeping after the task finish
            assertTrue(q.spentNanos() >= 1_000_000_000L);
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> q.execute(() -> { }));
        } finally {
            pool.shutdownNow();
        }
    }
}
