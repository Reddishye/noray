package com.redactado.noray.service;

import com.redactado.noray.log.CircularLogJournal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ServiceManagerTest {

    @Test
    void testServiceLifecycleAndDependencyResolution() throws Exception {
        CircularLogJournal journal = new CircularLogJournal(100);
        try (ServiceManager sm = new ServiceManager(journal)) {
            CountDownLatch dbStarted = new CountDownLatch(1);
            CountDownLatch webStarted = new CountDownLatch(1);

            sm.register(ServiceDescriptor.builder("db.service", ctx -> {
                dbStarted.countDown();
                while (!ctx.isStopRequested()) {
                    Thread.sleep(10);
                }
            }).build());

            sm.register(ServiceDescriptor.builder("web.service", ctx -> {
                webStarted.countDown();
                while (!ctx.isStopRequested()) {
                    Thread.sleep(10);
                }
            }).dependencies(java.util.Set.of("db.service")).build());

            sm.startService("web.service");

            assertTrue(dbStarted.await(2, TimeUnit.SECONDS));
            assertTrue(webStarted.await(2, TimeUnit.SECONDS));

            ServiceInfo webInfo = sm.getServiceInfo("web.service");
            ServiceInfo dbInfo = sm.getServiceInfo("db.service");

            assertEquals(ServiceStatus.RUNNING, webInfo.status());
            assertEquals(ServiceStatus.RUNNING, dbInfo.status());

            // Stopping db should stop dependent web
            sm.stopService("db.service");
            assertEquals(ServiceStatus.STOPPED, sm.getServiceInfo("web.service").status());
            assertEquals(ServiceStatus.STOPPED, sm.getServiceInfo("db.service").status());
        }
    }

    @Test
    void testServiceRestartPolicyOnFailure() throws Exception {
        CircularLogJournal journal = new CircularLogJournal(100);
        try (ServiceManager sm = new ServiceManager(journal)) {
            AtomicInteger runCount = new AtomicInteger(0);
            CountDownLatch secondRunLatch = new CountDownLatch(2);

            sm.register(ServiceDescriptor.builder("flaky.service", ctx -> {
                int count = runCount.incrementAndGet();
                secondRunLatch.countDown();
                if (count == 1) {
                    throw new RuntimeException("Simulated service crash");
                }
                while (!ctx.isStopRequested()) {
                    Thread.sleep(10);
                }
            })
            .restartPolicy(RestartPolicy.ON_FAILURE)
            .restartDelay(Duration.ofMillis(50))
            .maxRestartRetries(3)
            .build());

            sm.startService("flaky.service");
            assertTrue(secondRunLatch.await(3, TimeUnit.SECONDS));
            assertTrue(runCount.get() >= 2);
        }
    }
}
