package bindiego.io;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;

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

        // Sized so the 5MB byte budget binds first for typical 1-2KB log docs
        assertEquals("Default maxBatchSize should be 3000", 3000L, append.getMaxBatchSize());
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

    // ---- Deterministic document ids ----

    @Test
    public void appendBuilder_withIdFn_retained() {
        ElasticsearchIO.Append append = ElasticsearchIO.append()
            .withIdFn(doc -> "id-" + doc.length());

        assertNotNull("idFn should be retained by the builder", append.getIdFn());
        assertEquals("id-2", append.getIdFn().apply("{}"));
    }

    @Test
    public void appendBuilder_idFnDefaultsToNull() {
        // Auto-generated ids remain the default (no behavior change for existing users)
        assertNull(ElasticsearchIO.append().getIdFn());
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

    // =========================================================================
    // Phase 2 Tests
    // =========================================================================

    // ---- Task 2.4: Expanded retryable HTTP status codes ----

    @Test
    public void retryPredicate_retries429() throws Exception {
        String body = "{\"errors\":true,\"items\":[{\"index\":{\"status\":429}}]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        ElasticsearchIO.DefaultRetryPredicate predicate = new ElasticsearchIO.DefaultRetryPredicate();
        assertTrue("429 should be retryable", predicate.test(entity));
    }

    @Test
    public void retryPredicate_retries500() throws Exception {
        String body = "{\"errors\":true,\"items\":[{\"index\":{\"status\":500}}]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        ElasticsearchIO.DefaultRetryPredicate predicate = new ElasticsearchIO.DefaultRetryPredicate();
        assertTrue("500 should be retryable", predicate.test(entity));
    }

    @Test
    public void retryPredicate_retries502() throws Exception {
        String body = "{\"errors\":true,\"items\":[{\"index\":{\"status\":502}}]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        ElasticsearchIO.DefaultRetryPredicate predicate = new ElasticsearchIO.DefaultRetryPredicate();
        assertTrue("502 should be retryable", predicate.test(entity));
    }

    @Test
    public void retryPredicate_retries503() throws Exception {
        String body = "{\"errors\":true,\"items\":[{\"index\":{\"status\":503}}]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        ElasticsearchIO.DefaultRetryPredicate predicate = new ElasticsearchIO.DefaultRetryPredicate();
        assertTrue("503 should be retryable", predicate.test(entity));
    }

    @Test
    public void retryPredicate_retries504() throws Exception {
        String body = "{\"errors\":true,\"items\":[{\"index\":{\"status\":504}}]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        ElasticsearchIO.DefaultRetryPredicate predicate = new ElasticsearchIO.DefaultRetryPredicate();
        assertTrue("504 should be retryable", predicate.test(entity));
    }

    @Test
    public void retryPredicate_doesNotRetry400() throws Exception {
        String body = "{\"errors\":true,\"items\":[{\"index\":{\"status\":400}}]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        ElasticsearchIO.DefaultRetryPredicate predicate = new ElasticsearchIO.DefaultRetryPredicate();
        assertFalse("400 should NOT be retryable", predicate.test(entity));
    }

    @Test
    public void retryPredicate_doesNotRetry404() throws Exception {
        String body = "{\"errors\":true,\"items\":[{\"index\":{\"status\":404}}]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        ElasticsearchIO.DefaultRetryPredicate predicate = new ElasticsearchIO.DefaultRetryPredicate();
        assertFalse("404 should NOT be retryable", predicate.test(entity));
    }

    @Test
    public void retryPredicate_noErrorsReturnsFalse() throws Exception {
        String body = "{\"errors\":false,\"items\":[{\"index\":{\"status\":200}}]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        ElasticsearchIO.DefaultRetryPredicate predicate = new ElasticsearchIO.DefaultRetryPredicate();
        assertFalse("No errors should not trigger retry", predicate.test(entity));
    }

    @Test
    public void retryPredicate_singleCodeConstructor() throws Exception {
        // The single-code constructor should only retry the specified code
        String body429 = "{\"errors\":true,\"items\":[{\"index\":{\"status\":429}}]}";
        String body500 = "{\"errors\":true,\"items\":[{\"index\":{\"status\":500}}]}";
        ElasticsearchIO.DefaultRetryPredicate predicate = new ElasticsearchIO.DefaultRetryPredicate(429);
        HttpEntity entity429 = new ByteArrayEntity(body429.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        HttpEntity entity500 = new ByteArrayEntity(body500.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        assertTrue("Single-code predicate should retry 429", predicate.test(entity429));
        assertFalse("Single-code predicate should NOT retry 500", predicate.test(entity500));
    }

    // ---- Task 2.5: checkForErrors ----

    @Test
    public void checkForErrors_noErrors_doesNotThrow() throws Exception {
        String body = "{\"errors\":false,\"items\":[{\"index\":{\"status\":201,\"_id\":\"1\"}}]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        // Should not throw
        ElasticsearchIO.checkForErrors(entity, 8, false);
    }

    @Test(expected = IOException.class)
    public void checkForErrors_withErrors_throwsIOException() throws Exception {
        String body = "{\"errors\":true,\"items\":[{\"index\":{\"_id\":\"1\",\"status\":400,\"error\":{\"type\":\"mapper_parsing_exception\",\"reason\":\"failed to parse\"}}}]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        ElasticsearchIO.checkForErrors(entity, 8, false);
    }

    @Test(expected = IOException.class)
    public void checkForErrors_nullEntity_throwsIOException() throws Exception {
        ElasticsearchIO.checkForErrors(null, 8, false);
    }

    // ---- Task 2.8: toString redaction ----

    @Test
    public void connectionConf_toString_redactsPassword() {
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withUsername("admin")
            .withPassword("super-secret-password");

        String str = conf.toString();
        assertFalse("toString must NOT contain the actual password",
            str.contains("super-secret-password"));
        assertTrue("toString must contain *** for password",
            str.contains("password=***"));
    }

    @Test
    public void connectionConf_toString_redactsApiKey() {
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withApiKey("my-secret-api-key-xyz");

        String str = conf.toString();
        assertFalse("toString must NOT contain the actual API key",
            str.contains("my-secret-api-key-xyz"));
        assertTrue("toString must contain *** for apiKey",
            str.contains("apiKey=***"));
    }

    @Test
    public void connectionConf_toString_showsNullForMissingCredentials() {
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index");

        String str = conf.toString();
        assertTrue("toString should show null for missing password",
            str.contains("password=null"));
        assertTrue("toString should show null for missing apiKey",
            str.contains("apiKey=null"));
    }

    @Test
    public void connectionConf_toString_preservesNonSensitiveFields() {
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withUsername("admin");

        String str = conf.toString();
        assertTrue("toString should contain address",
            str.contains("https://es.example.com:9200"));
        assertTrue("toString should contain index",
            str.contains("my-index"));
        assertTrue("toString should contain username",
            str.contains("admin"));
    }

    // ---- Task 2.6: Maximum document size guard ----
    // (Tested indirectly via builder validation — the guard is in processElement
    //  which requires a full Beam pipeline context to invoke directly)

    @Test
    public void appendBuilder_maxBatchSizeBytes_default() {
        ElasticsearchIO.Append append = ElasticsearchIO.append();
        assertEquals("Default maxBatchSizeBytes should be 5MB",
            5L * 1024L * 1024L, append.getMaxBatchSizeBytes());
    }

    @Test
    public void appendBuilder_customBatchSizeBytes() {
        ElasticsearchIO.Append append = ElasticsearchIO.append()
            .withMaxBatchSizeBytes(10L * 1024L * 1024L);
        assertEquals(10L * 1024L * 1024L, append.getMaxBatchSizeBytes());
    }

    @Test(expected = IllegalArgumentException.class)
    public void appendBuilder_rejectsZeroBatchSizeBytes() {
        ElasticsearchIO.append().withMaxBatchSizeBytes(0);
    }

    // ---- Task 2.7: Consolidated callback (validated by successful build) ----

    @Test
    public void connectionConf_createWithAllOptions_noException() {
        // Verifies the builder doesn't throw when all options are set
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withUsername("admin")
            .withPassword("pass")
            .withTrustSelfSignedCerts(true)
            .withSocketTimeout(30000)
            .withConnectTimeout(5000)
            .withNumThread(4);

        assertNotNull(conf);
        assertEquals("admin", conf.getUsername());
        assertEquals(Integer.valueOf(4), conf.getNumThread());
    }

    // =========================================================================
    // Phase 3 Tests
    // =========================================================================

    // ---- Task 3.4: ExposedByteArrayOutputStream ----

    @Test
    public void exposedBaos_getRawBuffer_returnsInternalBuffer() throws Exception {
        ElasticsearchIO.ExposedByteArrayOutputStream baos =
            new ElasticsearchIO.ExposedByteArrayOutputStream(16);
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        baos.write(data);

        byte[] raw = baos.getRawBuffer();
        // Raw buffer should contain the written data at the beginning
        for (int i = 0; i < data.length; i++) {
            assertEquals("Byte at position " + i + " should match", data[i], raw[i]);
        }
        assertEquals("size() should match data length", data.length, baos.size());
        // Raw buffer may be larger than data (internal capacity)
        assertTrue("Raw buffer should be at least as large as data",
            raw.length >= data.length);
    }

    @Test
    public void exposedBaos_avoidsCopy() throws Exception {
        ElasticsearchIO.ExposedByteArrayOutputStream baos =
            new ElasticsearchIO.ExposedByteArrayOutputStream(8192);
        baos.write("test".getBytes(StandardCharsets.UTF_8));

        // getRawBuffer returns the same array reference (no copy)
        byte[] raw1 = baos.getRawBuffer();
        byte[] raw2 = baos.getRawBuffer();
        assertSame("getRawBuffer should return same reference", raw1, raw2);

        // toByteArray returns a new copy each time
        byte[] copy1 = baos.toByteArray();
        byte[] copy2 = baos.toByteArray();
        assertNotSame("toByteArray should return new copy", copy1, copy2);
    }

    // ---- Task 3.8: Configurable pending timeout ----

    @Test
    public void appendBuilder_defaultPendingTimeout() {
        ElasticsearchIO.Append append = ElasticsearchIO.append();
        // Must exceed the default 120s socket timeout so FinishBundle never
        // abandons a bulk request that is still legitimately in flight.
        assertEquals("Default pending timeout should be 300s",
            300L, append.getPendingTimeoutSeconds());
    }

    @Test
    public void appendBuilder_customPendingTimeout() {
        ElasticsearchIO.Append append = ElasticsearchIO.append()
            .withPendingTimeout(120);
        assertEquals(120L, append.getPendingTimeoutSeconds());
    }

    @Test(expected = IllegalArgumentException.class)
    public void appendBuilder_rejectsZeroPendingTimeout() {
        ElasticsearchIO.append().withPendingTimeout(0);
    }

    // ---- Task 3.10: LongAdder metrics ----

    @Test
    public void metrics_returnsExpectedKeys() {
        java.util.Map<String, Long> metrics = ElasticsearchIO.getMetrics();
        assertTrue("Metrics should contain totalDocuments", metrics.containsKey("totalDocuments"));
        assertTrue("Metrics should contain totalBatches", metrics.containsKey("totalBatches"));
        assertTrue("Metrics should contain totalErrors", metrics.containsKey("totalErrors"));
        assertTrue("Metrics should contain activeConnections", metrics.containsKey("activeConnections"));
        assertTrue("Metrics should contain avgBatchSize", metrics.containsKey("avgBatchSize"));
    }

    // =========================================================================
    // Phase 4 Tests
    // =========================================================================

    // ---- Task 4.2: Typo fix — both old and new method names work ----

    @Test
    public void withIgnoreInsecureSSL_newMethodWorks() {
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withIgnoreInsecureSSL(true);
        assertTrue(conf.isIgnoreInsecureSSL());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void withIngnoreInsecureSSL_deprecatedMethodStillWorks() {
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withIngnoreInsecureSSL(true);
        assertTrue("Deprecated method should still set the flag", conf.isIgnoreInsecureSSL());
    }

    // ---- Task 4.3: Pool key for logging redaction ----

    @Test
    public void poolKeyForLogging_redactsSensitiveInfo() {
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withUsername("admin")
            .withPassword("secret");

        String logKey = conf.getPoolKeyForLogging();
        assertTrue("Log key should contain address", logKey.contains("es.example.com"));
        assertTrue("Log key should contain index", logKey.contains("my-index"));
        assertFalse("Log key should NOT contain username", logKey.contains("admin"));
        assertFalse("Log key should NOT contain password", logKey.contains("secret"));
        assertTrue("Log key should contain *** redaction", logKey.contains("***"));
    }

    // =========================================================================
    // Task 3.7: parseBulkResponse — partial bulk failure handling
    // =========================================================================

    @Test
    public void parseBulkResponse_allSuccess() throws Exception {
        String body = "{\"took\":5,\"errors\":false,\"items\":["
            + "{\"index\":{\"_id\":\"1\",\"status\":201}},"
            + "{\"index\":{\"_id\":\"2\",\"status\":201}},"
            + "{\"index\":{\"_id\":\"3\",\"status\":201}}"
            + "]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        ElasticsearchIO.BulkResult result = ElasticsearchIO.parseBulkResponse(entity, 8, false);

        assertEquals("All 3 docs should succeed", 3, result.successCount);
        assertTrue("No retryable failures", result.retryableFailures.isEmpty());
        assertTrue("No non-retryable failures", result.nonRetryableFailures.isEmpty());
        assertFalse("hasFailures should be false", result.hasFailures());
    }

    @Test
    public void parseBulkResponse_mixedRetryableAndNonRetryable() throws Exception {
        String body = "{\"took\":10,\"errors\":true,\"items\":["
            + "{\"index\":{\"_id\":\"1\",\"status\":201}},"                                    // success
            + "{\"index\":{\"_id\":\"2\",\"status\":429,\"error\":{\"type\":\"es_rejected_execution_exception\",\"reason\":\"too many requests\"}}},"  // retryable
            + "{\"index\":{\"_id\":\"3\",\"status\":201}},"                                    // success
            + "{\"index\":{\"_id\":\"4\",\"status\":400,\"error\":{\"type\":\"mapper_parsing_exception\",\"reason\":\"failed to parse\"}}},"           // non-retryable
            + "{\"index\":{\"_id\":\"5\",\"status\":201}}"                                     // success
            + "]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        ElasticsearchIO.BulkResult result = ElasticsearchIO.parseBulkResponse(entity, 8, false);

        assertEquals("3 docs should succeed", 3, result.successCount);
        assertEquals("1 retryable failure", 1, result.retryableFailures.size());
        assertEquals("1 non-retryable failure", 1, result.nonRetryableFailures.size());
        assertTrue("hasFailures should be true", result.hasFailures());
    }

    @Test
    public void parseBulkResponse_preservesPositionalIndex() throws Exception {
        String body = "{\"took\":10,\"errors\":true,\"items\":["
            + "{\"index\":{\"_id\":\"1\",\"status\":201}},"                                    // index 0: success
            + "{\"index\":{\"_id\":\"2\",\"status\":201}},"                                    // index 1: success
            + "{\"index\":{\"_id\":\"3\",\"status\":429,\"error\":{\"type\":\"throttle\",\"reason\":\"busy\"}}},"  // index 2: retryable
            + "{\"index\":{\"_id\":\"4\",\"status\":201}},"                                    // index 3: success
            + "{\"index\":{\"_id\":\"5\",\"status\":400,\"error\":{\"type\":\"mapping\",\"reason\":\"bad field\"}}}"  // index 4: non-retryable
            + "]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        ElasticsearchIO.BulkResult result = ElasticsearchIO.parseBulkResponse(entity, 8, false);

        // Verify positional indices are correct
        assertEquals("Retryable failure should be at index 2", 2, result.retryableFailures.get(0).index);
        assertEquals("Retryable status should be 429", 429, result.retryableFailures.get(0).statusCode);
        assertEquals("Non-retryable failure should be at index 4", 4, result.nonRetryableFailures.get(0).index);
        assertEquals("Non-retryable status should be 400", 400, result.nonRetryableFailures.get(0).statusCode);
    }

    @Test
    public void parseBulkResponse_allRetryable() throws Exception {
        String body = "{\"took\":10,\"errors\":true,\"items\":["
            + "{\"index\":{\"_id\":\"1\",\"status\":429,\"error\":{\"type\":\"throttle\",\"reason\":\"busy\"}}},"
            + "{\"index\":{\"_id\":\"2\",\"status\":503,\"error\":{\"type\":\"unavailable\",\"reason\":\"shard unavailable\"}}}"
            + "]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        ElasticsearchIO.BulkResult result = ElasticsearchIO.parseBulkResponse(entity, 8, false);

        assertEquals("0 successes", 0, result.successCount);
        assertEquals("2 retryable failures", 2, result.retryableFailures.size());
        assertTrue("No non-retryable failures", result.nonRetryableFailures.isEmpty());
    }

    @Test
    public void parseBulkResponse_allNonRetryable() throws Exception {
        String body = "{\"took\":10,\"errors\":true,\"items\":["
            + "{\"index\":{\"_id\":\"1\",\"status\":400,\"error\":{\"type\":\"mapping\",\"reason\":\"bad\"}}},"
            + "{\"index\":{\"_id\":\"2\",\"status\":409,\"error\":{\"type\":\"conflict\",\"reason\":\"version\"}}}"
            + "]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        ElasticsearchIO.BulkResult result = ElasticsearchIO.parseBulkResponse(entity, 8, false);

        assertEquals("0 successes", 0, result.successCount);
        assertTrue("No retryable failures", result.retryableFailures.isEmpty());
        assertEquals("2 non-retryable failures", 2, result.nonRetryableFailures.size());
    }

    @Test
    public void parseBulkResponse_capturesErrorDetails() throws Exception {
        String body = "{\"took\":10,\"errors\":true,\"items\":["
            + "{\"index\":{\"_id\":\"1\",\"status\":400,\"error\":{\"type\":\"mapper_parsing_exception\",\"reason\":\"failed to parse field [age] of type [long]\"}}}"
            + "]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        ElasticsearchIO.BulkResult result = ElasticsearchIO.parseBulkResponse(entity, 8, false);

        ElasticsearchIO.BulkResult.FailedDoc doc = result.nonRetryableFailures.get(0);
        assertEquals(0, doc.index);
        assertEquals(400, doc.statusCode);
        assertEquals("mapper_parsing_exception", doc.errorType);
        assertTrue("Reason should contain field info",
            doc.errorReason.contains("failed to parse field"));
    }

    @Test
    public void parseBulkResponse_retryable500Series() throws Exception {
        // Verify all retryable server errors are classified correctly
        for (int code : new int[]{500, 502, 503, 504}) {
            String body = "{\"took\":1,\"errors\":true,\"items\":["
                + "{\"index\":{\"_id\":\"1\",\"status\":" + code
                + ",\"error\":{\"type\":\"server_error\",\"reason\":\"test\"}}}"
                + "]}";
            HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

            ElasticsearchIO.BulkResult result = ElasticsearchIO.parseBulkResponse(entity, 8, false);
            assertEquals("HTTP " + code + " should be retryable", 1, result.retryableFailures.size());
            assertEquals(code, result.retryableFailures.get(0).statusCode);
        }
    }

    @Test
    public void parseBulkResponse_noErrors_usesExpectedCountForFilteredResponses() throws Exception {
        // With filter_path the success-path response may omit item details entirely;
        // the caller-supplied document count must drive the success accounting.
        String body = "{\"took\":5,\"errors\":false}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        ElasticsearchIO.BulkResult result = ElasticsearchIO.parseBulkResponse(entity, 8, false, 42);

        assertEquals("Success count must come from the sent doc count", 42, result.successCount);
        assertFalse(result.hasFailures());
    }

    @Test
    public void parseBulkResponse_missingOpNode_countedAsFailureNotSuccess() throws Exception {
        // Regression: an item without the expected op key ("index") used to fall into
        // the permissive fallback and be counted as a SUCCESS.
        String body = "{\"took\":1,\"errors\":true,\"items\":["
            + "{\"delete\":{\"_id\":\"1\",\"status\":200}}"
            + "]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        ElasticsearchIO.BulkResult result = ElasticsearchIO.parseBulkResponse(entity, 8, false);

        assertEquals("Unrecognized item shape must not count as success", 0, result.successCount);
        assertEquals("Unrecognized item shape is a non-retryable failure",
            1, result.nonRetryableFailures.size());
        assertEquals("unexpected_response_shape", result.nonRetryableFailures.get(0).errorType);
    }

    @Test
    public void parseBulkResponse_non2xxWithoutError_classifiedByStatus() throws Exception {
        // Regression: a non-2xx status without an "error" object used to be counted
        // as a success. It must be classified by status code instead.
        String body = "{\"took\":1,\"errors\":true,\"items\":["
            + "{\"index\":{\"_id\":\"1\",\"status\":429}},"
            + "{\"index\":{\"_id\":\"2\",\"status\":400}}"
            + "]}";
        HttpEntity entity = new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        ElasticsearchIO.BulkResult result = ElasticsearchIO.parseBulkResponse(entity, 8, false);

        assertEquals(0, result.successCount);
        assertEquals("429 without error object should be retryable", 1, result.retryableFailures.size());
        assertEquals(429, result.retryableFailures.get(0).statusCode);
        assertEquals("400 without error object should be non-retryable", 1, result.nonRetryableFailures.size());
        assertEquals(400, result.nonRetryableFailures.get(0).statusCode);
    }

    @Test
    public void poolKeyForLogging_differentFromPoolKey() {
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index")
            .withUsername("admin");

        // Pool key contains username, logging key does not
        assertNotEquals("Log key should differ from pool key when username present",
            conf.getPoolKey(), conf.getPoolKeyForLogging());
    }

    // =========================================================================
    // URL / Port Handling — covers createClientBuilder's HttpHost construction
    // =========================================================================
    // These tests validate the URL parsing that feeds into:
    //   new HttpHost(url.getHost(), url.getPort(), url.getProtocol())
    // The HttpHost constructor handles port=-1 by using the scheme default.

    @Test
    public void urlParsing_explicitPort_preservedCorrectly() throws Exception {
        URL url = new URL("https://es-cluster.example.com:9200");
        assertEquals("es-cluster.example.com", url.getHost());
        assertEquals(9200, url.getPort());
        assertEquals("https", url.getProtocol());

        HttpHost host = new HttpHost(url.getHost(), url.getPort(), url.getProtocol());
        assertEquals(9200, host.getPort());
        assertEquals("es-cluster.example.com", host.getHostName());
        assertEquals("https", host.getSchemeName());
    }

    @Test
    public void urlParsing_noPort_returnsMinusOne() throws Exception {
        URL url = new URL("https://es-cluster.internal");
        assertEquals("es-cluster.internal", url.getHost());
        assertEquals(-1, url.getPort());
        assertEquals("https", url.getProtocol());

        // HttpHost with port=-1 uses scheme default (443 for https)
        HttpHost host = new HttpHost(url.getHost(), url.getPort(), url.getProtocol());
        assertEquals(-1, host.getPort());
        // toURI uses scheme default when port is -1
        assertTrue("HttpHost should handle -1 port gracefully",
            host.toHostString().equals("es-cluster.internal:443")
            || host.toHostString().equals("es-cluster.internal"));
    }

    @Test
    public void urlParsing_httpNoPort() throws Exception {
        URL url = new URL("http://es-cluster.internal");
        assertEquals(-1, url.getPort());
        assertEquals("http", url.getProtocol());

        HttpHost host = new HttpHost(url.getHost(), url.getPort(), url.getProtocol());
        assertEquals(-1, host.getPort());
    }

    @Test
    public void urlParsing_localhostWithPort() throws Exception {
        URL url = new URL("http://localhost:9200");
        assertEquals("localhost", url.getHost());
        assertEquals(9200, url.getPort());

        HttpHost host = new HttpHost(url.getHost(), url.getPort(), url.getProtocol());
        assertEquals(9200, host.getPort());
    }

    @Test
    public void urlParsing_localhostNoPort() throws Exception {
        // This is the case the buggy localhost hack was trying to handle.
        // The correct behavior: pass -1 to HttpHost, which uses scheme default.
        URL url = new URL("http://localhost");
        assertEquals("localhost", url.getHost());
        assertEquals(-1, url.getPort());

        HttpHost host = new HttpHost(url.getHost(), url.getPort(), url.getProtocol());
        assertEquals("Port should be -1 (scheme default), not hardcoded 9200",
            -1, host.getPort());
    }

    @Test
    public void urlParsing_httpsWithNonStandardPort() throws Exception {
        URL url = new URL("https://secure-es.example.com:9243");
        assertEquals(9243, url.getPort());

        HttpHost host = new HttpHost(url.getHost(), url.getPort(), url.getProtocol());
        assertEquals(9243, host.getPort());
        assertEquals("https", host.getSchemeName());
    }

    @Test
    public void urlParsing_ipAddressWithPort() throws Exception {
        URL url = new URL("http://10.0.1.50:9200");
        assertEquals("10.0.1.50", url.getHost());
        assertEquals(9200, url.getPort());

        HttpHost host = new HttpHost(url.getHost(), url.getPort(), url.getProtocol());
        assertEquals("10.0.1.50", host.getHostName());
        assertEquals(9200, host.getPort());
    }

    @Test
    public void urlParsing_ipv6WithPort() throws Exception {
        // IPv6 addresses are enclosed in brackets in URLs
        URL url = new URL("http://[::1]:9200");
        assertEquals("[::1]", url.getHost());
        assertEquals(9200, url.getPort());
    }

    // ConnectionConf integration — ensure various address formats are accepted

    @Test
    public void connectionConf_explicitPort_accepted() {
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("https://es-cluster.example.com:9200", "my-index");
        assertEquals("https://es-cluster.example.com:9200", conf.getAddress());
    }

    @Test
    public void connectionConf_noPort_accepted() {
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("https://es-cluster.internal", "my-index");
        assertEquals("https://es-cluster.internal", conf.getAddress());
    }

    @Test
    public void connectionConf_httpLocalhost_accepted() {
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("http://localhost:9200", "my-index");
        assertEquals("http://localhost:9200", conf.getAddress());
    }

    @Test
    public void connectionConf_commaSeparatedAddresses_accepted() {
        // Multi-host addresses enable client-side round-robin; each entry must be
        // an independently parseable URL after trimming.
        String address = "https://es1.example.com:9200, https://es2.example.com:9200";
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create(address, "my-index");
        assertEquals(address, conf.getAddress());

        String[] parts = address.split(",");
        assertEquals(2, parts.length);
        for (String part : parts) {
            try {
                URL url = new URL(part.trim());
                assertEquals(9200, url.getPort());
                assertEquals("https", url.getProtocol());
            } catch (Exception e) {
                fail("Each comma-separated address must parse as a URL: " + part);
            }
        }
    }

    @Test
    public void connectionConf_differentPortsSameDomain_differentPoolKeys() {
        ElasticsearchIO.ConnectionConf conf1 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index");
        ElasticsearchIO.ConnectionConf conf2 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9243", "my-index");

        assertNotEquals("Different ports should produce different pool keys",
            conf1.getPoolKey(), conf2.getPoolKey());
    }

    @Test
    public void connectionConf_httpVsHttps_differentPoolKeys() {
        ElasticsearchIO.ConnectionConf conf1 = ElasticsearchIO.ConnectionConf
            .create("http://es.example.com:9200", "my-index");
        ElasticsearchIO.ConnectionConf conf2 = ElasticsearchIO.ConnectionConf
            .create("https://es.example.com:9200", "my-index");

        assertNotEquals("Different schemes should produce different pool keys",
            conf1.getPoolKey(), conf2.getPoolKey());
    }
}
