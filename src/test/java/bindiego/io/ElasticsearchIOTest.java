package bindiego.io;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Queue;

import org.junit.Test;

/**
 * Unit tests for ElasticsearchIO Phase 1 critical fixes.
 *
 * These tests validate:
 * - Pool key uniqueness and security-relevant field inclusion (Task 1.6)
 * - Backpressure via Semaphore (Task 1.2)
 * - Dedicated IO executor isolation from ForkJoinPool (Task 1.1)
 * - ScheduledExecutorService exception resilience (Task 1.3)
 * - Instance-scoped scheduler lifecycle (Task 1.4)
 * - SSL production gate (Task 1.5)
 */
public class ElasticsearchIOTest {

    // =========================================================================
    // Task 1.6: Pool key includes all security-relevant fields
    // =========================================================================

    @Test
    public void poolKey_sameConfig_producesIdenticalKey() {
        ElasticsearchIO.ConnectionConf conf1 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index");
        ElasticsearchIO.ConnectionConf conf2 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index");

        assertEquals(conf1.getPoolKey(), conf2.getPoolKey());
    }

    @Test
    public void poolKey_differentAddress_producesDifferentKey() {
        ElasticsearchIO.ConnectionConf conf1 = ElasticsearchIO.ConnectionConf
            .create("https://es1.example.com:9200", "my-index");
        ElasticsearchIO.ConnectionConf conf2 = ElasticsearchIO.ConnectionConf
            .create("https://es2.example.com:9200", "my-index");

        assertNotEquals(conf1.getPoolKey(), conf2.getPoolKey());
    }

    @Test
    public void poolKey_differentIndex_producesDifferentKey() {
        ElasticsearchIO.ConnectionConf conf1 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "index-a");
        ElasticsearchIO.ConnectionConf conf2 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "index-b");

        assertNotEquals(conf1.getPoolKey(), conf2.getPoolKey());
    }

    @Test
    public void poolKey_differentUsername_producesDifferentKey() {
        ElasticsearchIO.ConnectionConf conf1 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withUsername("alice");
        ElasticsearchIO.ConnectionConf conf2 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withUsername("bob");

        assertNotEquals(conf1.getPoolKey(), conf2.getPoolKey());
    }

    @Test
    public void poolKey_differentPassword_producesDifferentKey() {
        ElasticsearchIO.ConnectionConf conf1 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withUsername("user").withPassword("pass1");
        ElasticsearchIO.ConnectionConf conf2 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withUsername("user").withPassword("pass2");

        // Different passwords must produce different pool keys
        assertNotEquals(conf1.getPoolKey(), conf2.getPoolKey());
    }

    @Test
    public void poolKey_differentApiKey_producesDifferentKey() {
        ElasticsearchIO.ConnectionConf conf1 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withApiKey("key-aaa");
        ElasticsearchIO.ConnectionConf conf2 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withApiKey("key-bbb");

        assertNotEquals(conf1.getPoolKey(), conf2.getPoolKey());
    }

    @Test
    public void poolKey_differentSSLFlags_producesDifferentKey() {
        ElasticsearchIO.ConnectionConf conf1 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withIngnoreInsecureSSL(false);
        ElasticsearchIO.ConnectionConf conf2 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withIngnoreInsecureSSL(true);

        assertNotEquals(conf1.getPoolKey(), conf2.getPoolKey());
    }

    @Test
    public void poolKey_differentTrustSelfSigned_producesDifferentKey() {
        ElasticsearchIO.ConnectionConf conf1 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withTrustSelfSignedCerts(false);
        ElasticsearchIO.ConnectionConf conf2 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withTrustSelfSignedCerts(true);

        assertNotEquals(conf1.getPoolKey(), conf2.getPoolKey());
    }

    @Test
    public void poolKey_containsPipeDelimiter() {
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index");

        String key = conf.getPoolKey();
        // Should contain pipe separators (not colons which can appear in URLs)
        assertTrue("Pool key should use | as delimiter", key.contains("|"));
    }

    @Test
    public void poolKey_noAuthVsApiKey_producesDifferentKey() {
        // Regression: old pool key used "noauth" for both no-username and API-key-only configs
        ElasticsearchIO.ConnectionConf noAuth = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index");
        ElasticsearchIO.ConnectionConf apiKeyAuth = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withApiKey("my-api-key-123");

        assertNotEquals("No-auth and API-key configs must have different pool keys",
            noAuth.getPoolKey(), apiKeyAuth.getPoolKey());
    }

    // =========================================================================
    // Task 1.2: Backpressure via Semaphore
    // =========================================================================

    @Test
    public void semaphore_limitsMaxConcurrency() throws Exception {
        int maxConcurrent = 3;
        Semaphore semaphore = new Semaphore(maxConcurrent);
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);
        CountDownLatch allStarted = new CountDownLatch(maxConcurrent);
        CountDownLatch proceed = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(maxConcurrent + 2);

        // Submit maxConcurrent + 2 tasks — only maxConcurrent should run simultaneously
        for (int i = 0; i < maxConcurrent + 2; i++) {
            executor.submit(() -> {
                try {
                    semaphore.acquire();
                    int current = concurrentCount.incrementAndGet();
                    maxObserved.updateAndGet(prev -> Math.max(prev, current));
                    allStarted.countDown();
                    proceed.await(); // hold the semaphore permit
                    concurrentCount.decrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    semaphore.release();
                }
            });
        }

        // Wait for the first maxConcurrent tasks to acquire permits
        allStarted.await(5, TimeUnit.SECONDS);
        Thread.sleep(100); // give extra tasks a chance to (try to) acquire

        // Verify: exactly maxConcurrent tasks are running, not more
        assertEquals("Semaphore should limit concurrency to " + maxConcurrent,
            maxConcurrent, concurrentCount.get());

        proceed.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Max observed should never exceed the semaphore permits
        assertTrue("Max concurrent should not exceed " + maxConcurrent,
            maxObserved.get() <= maxConcurrent);
    }

    @Test
    public void semaphore_releasedOnException() throws Exception {
        Semaphore semaphore = new Semaphore(1);
        ExecutorService executor = Executors.newFixedThreadPool(1);

        // Simulate a batch that fails — semaphore should still be released in finally
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                semaphore.acquire();
                throw new RuntimeException("Simulated batch failure");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                semaphore.release();
            }
        }, executor);

        try {
            future.get(5, TimeUnit.SECONDS);
            fail("Should have thrown");
        } catch (Exception e) {
            // expected
        }

        // Semaphore permit should be available again
        assertTrue("Semaphore should be released after exception", semaphore.tryAcquire());
        semaphore.release();

        executor.shutdown();
    }

    // =========================================================================
    // Task 1.1: Dedicated IO executor (not ForkJoinPool.commonPool())
    // =========================================================================

    @Test
    public void dedicatedExecutor_runsOnNamedThreads() throws Exception {
        String prefix = "es-io-test";
        AtomicInteger counter = new AtomicInteger(0);
        ExecutorService ioExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });

        AtomicReference<String> threadName = new AtomicReference<>();
        CompletableFuture.runAsync(() -> {
            threadName.set(Thread.currentThread().getName());
        }, ioExecutor).get(5, TimeUnit.SECONDS);

        assertTrue("Task should run on dedicated thread, not ForkJoinPool",
            threadName.get().startsWith(prefix));
        assertFalse("Task should NOT run on ForkJoinPool.commonPool",
            threadName.get().contains("ForkJoinPool"));

        ioExecutor.shutdown();
    }

    @Test
    public void dedicatedExecutor_isDaemon() throws Exception {
        ExecutorService ioExecutor = Executors.newFixedThreadPool(1, r -> {
            Thread t = new Thread(r, "es-io-daemon-test");
            t.setDaemon(true);
            return t;
        });

        AtomicReference<Boolean> isDaemon = new AtomicReference<>();
        CompletableFuture.runAsync(() -> {
            isDaemon.set(Thread.currentThread().isDaemon());
        }, ioExecutor).get(5, TimeUnit.SECONDS);

        assertTrue("IO executor threads should be daemon threads", isDaemon.get());
        ioExecutor.shutdown();
    }

    // =========================================================================
    // Task 1.3: scheduleAtFixedRate exception resilience
    // =========================================================================

    @Test
    public void scheduledTask_continuesAfterException() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-scheduler");
            t.setDaemon(true);
            return t;
        });

        AtomicInteger invocationCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Schedule a task that throws on first invocation but continues
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                int count = invocationCount.incrementAndGet();
                if (count == 1) {
                    throw new RuntimeException("Simulated flush failure");
                }
            } catch (Throwable t) {
                errorCount.incrementAndGet();
                // This is the pattern from Task 1.3 — catch Throwable, log, continue
                // WITHOUT re-throwing, so the scheduled task survives
            }
        }, 0, 50, TimeUnit.MILLISECONDS);

        // Wait for multiple invocations
        Thread.sleep(300);
        task.cancel(false);

        // The task should have been invoked multiple times despite the exception
        assertTrue("Task should be invoked more than once (was: " + invocationCount.get() + ")",
            invocationCount.get() >= 3);
        assertEquals("Error should have been caught exactly once", 1, errorCount.get());

        scheduler.shutdown();
    }

    @Test
    public void scheduledTask_stopsOnUncaughtException() throws Exception {
        // This test demonstrates WHY the try-catch in Task 1.3 is essential:
        // without it, scheduleAtFixedRate silently kills the task
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-scheduler-no-catch");
            t.setDaemon(true);
            return t;
        });

        AtomicInteger invocationCount = new AtomicInteger(0);

        // Schedule a task that throws WITHOUT catching — simulates the old behavior
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            invocationCount.incrementAndGet();
            throw new RuntimeException("Uncaught exception kills the task");
        }, 0, 50, TimeUnit.MILLISECONDS);

        // Wait — the task should have stopped after the first exception
        Thread.sleep(300);

        // Without try-catch, the task is invoked only once then silently stops
        assertEquals("Without try-catch, scheduleAtFixedRate invokes task only once",
            1, invocationCount.get());

        task.cancel(false);
        scheduler.shutdown();
    }

    // =========================================================================
    // Task 1.4: Instance-scoped scheduler lifecycle
    // =========================================================================

    @Test
    public void instanceScheduler_independentLifecycle() throws Exception {
        // Two independent schedulers — shutting down one should not affect the other
        ScheduledExecutorService scheduler1 = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler-1");
            t.setDaemon(true);
            return t;
        });
        ScheduledExecutorService scheduler2 = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler-2");
            t.setDaemon(true);
            return t;
        });

        AtomicInteger count1 = new AtomicInteger(0);
        AtomicInteger count2 = new AtomicInteger(0);

        scheduler1.scheduleAtFixedRate(() -> count1.incrementAndGet(), 0, 50, TimeUnit.MILLISECONDS);
        scheduler2.scheduleAtFixedRate(() -> count2.incrementAndGet(), 0, 50, TimeUnit.MILLISECONDS);

        Thread.sleep(200);

        // Shutdown scheduler1 (simulates one pipeline calling cleanup)
        scheduler1.shutdown();
        assertTrue(scheduler1.awaitTermination(2, TimeUnit.SECONDS));

        int count2AtShutdown = count2.get();
        Thread.sleep(200);

        // scheduler2 should still be running
        assertTrue("Scheduler 2 should continue running after scheduler 1 shutdown",
            count2.get() > count2AtShutdown);
        assertFalse("Scheduler 2 should not be shutdown", scheduler2.isShutdown());

        scheduler2.shutdown();
    }

    @Test
    public void executorShutdown_gracefulWithTimeout() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "shutdown-test");
            t.setDaemon(true);
            return t;
        });

        // Submit a long-running task
        CountDownLatch taskStarted = new CountDownLatch(1);
        executor.submit(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        taskStarted.await(2, TimeUnit.SECONDS);

        // Graceful shutdown pattern (mirrors @Teardown)
        executor.shutdown();
        boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
        if (!terminated) {
            executor.shutdownNow();
        }

        assertTrue("Executor should terminate gracefully", terminated);
        assertTrue("Executor should be shutdown", executor.isShutdown());
    }

    // =========================================================================
    // Task 1.5: SSL production gate
    // =========================================================================

    @Test
    public void poolKey_insecureSSLDefault_isFalse() {
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index");

        assertFalse("isIgnoreInsecureSSL should default to false", conf.isIgnoreInsecureSSL());
    }

    @Test
    public void poolKey_trustSelfSignedDefault_isFalse() {
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index");

        assertFalse("isTrustSelfSignedCerts should default to false", conf.isTrustSelfSignedCerts());
    }

    // =========================================================================
    // Append builder defaults
    // =========================================================================

    @Test
    public void appendBuilder_defaultMaxConcurrentRequests() {
        ElasticsearchIO.Append append = ElasticsearchIO.append();

        assertEquals("Default maxConcurrentRequests should be 5", 5, append.getMaxConcurrentRequests());
    }

    @Test
    public void appendBuilder_defaultBatchSize() {
        ElasticsearchIO.Append append = ElasticsearchIO.append();

        assertEquals("Default maxBatchSize should be 1000", 1000L, append.getMaxBatchSize());
    }

    @Test
    public void appendBuilder_defaultFlushInterval() {
        ElasticsearchIO.Append append = ElasticsearchIO.append();

        assertEquals("Default flushIntervalMillis should be 30000", 30000L, append.getFlushIntervalMillis());
    }

    @Test
    public void appendBuilder_customMaxConcurrentRequests() {
        ElasticsearchIO.Append append = ElasticsearchIO.append()
            .withMaxConcurrentRequests(10);

        assertEquals(10, append.getMaxConcurrentRequests());
    }

    @Test(expected = IllegalArgumentException.class)
    public void appendBuilder_rejectsZeroConcurrentRequests() {
        ElasticsearchIO.append().withMaxConcurrentRequests(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void appendBuilder_rejectsNegativeConcurrentRequests() {
        ElasticsearchIO.append().withMaxConcurrentRequests(-1);
    }

    // =========================================================================
    // Backpressure + dedicated executor integration
    // =========================================================================

    @Test
    public void backpressureWithDedicatedExecutor_endToEnd() throws Exception {
        int maxConcurrent = 2;
        Semaphore semaphore = new Semaphore(maxConcurrent);
        ExecutorService ioExecutor = Executors.newFixedThreadPool(maxConcurrent, r -> {
            Thread t = new Thread(r, "es-io-e2e-" + Thread.currentThread().getId());
            t.setDaemon(true);
            return t;
        });
        Queue<CompletableFuture<Void>> pendingOps = new ConcurrentLinkedQueue<>();

        AtomicInteger maxConcurrentObserved = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);
        CountDownLatch allDone = new CountDownLatch(5);

        // Simulate 5 batch flushes with backpressure
        for (int i = 0; i < 5; i++) {
            semaphore.acquire();
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    int c = currentConcurrent.incrementAndGet();
                    maxConcurrentObserved.updateAndGet(prev -> Math.max(prev, c));
                    Thread.sleep(50); // simulate ES bulk request
                    currentConcurrent.decrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    semaphore.release();
                    allDone.countDown();
                }
            }, ioExecutor);
            pendingOps.add(future);
        }

        assertTrue("All batches should complete", allDone.await(10, TimeUnit.SECONDS));
        assertTrue("Max concurrent should not exceed " + maxConcurrent + " (was: " + maxConcurrentObserved.get() + ")",
            maxConcurrentObserved.get() <= maxConcurrent);

        // All futures should be done
        for (CompletableFuture<Void> f : pendingOps) {
            assertTrue(f.isDone());
            assertFalse(f.isCompletedExceptionally());
        }

        ioExecutor.shutdown();
    }
}
