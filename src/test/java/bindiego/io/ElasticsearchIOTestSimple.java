package bindiego.io;

import static org.junit.Assert.*;

import java.util.Map;

import org.joda.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Simplified unit tests for {@link ElasticsearchIO} class.
 * Tests basic functionality without complex mocking.
 */
@RunWith(JUnit4.class)
public class ElasticsearchIOTestSimple {

    private ElasticsearchIO.ConnectionConf connectionConf;

    @Before
    public void setUp() {
        // Clear any existing metrics
        ElasticsearchIO.cleanup();
        
        connectionConf = ElasticsearchIO.ConnectionConf
            .create("http://localhost:9200", "test-index")
            .withUsername("testuser")
            .withPassword("testpass")
            .withSocketTimeout(30000)
            .withConnectTimeout(5000);
    }

    @After
    public void tearDown() {
        ElasticsearchIO.cleanup();
    }

    @Test
    public void testConnectionConf_basicCreation() {
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("http://localhost:9200", "test-index");
        
        assertEquals("http://localhost:9200", conf.getAddress());
        assertEquals("test-index", conf.getIndex());
        assertNull(conf.getUsername());
        assertNull(conf.getPassword());
        assertFalse(conf.isTrustSelfSignedCerts());
        assertFalse(conf.isIgnoreInsecureSSL());
    }

    @Test
    public void testConnectionConf_withAuthentication() {
        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
            .create("https://elastic.example.com:9243", "logs-index")
            .withUsername("elastic")
            .withPassword("secretpassword")
            .withApiKey("testApiKey");
        
        assertEquals("https://elastic.example.com:9243", conf.getAddress());
        assertEquals("logs-index", conf.getIndex());
        assertEquals("elastic", conf.getUsername());
        assertEquals("secretpassword", conf.getPassword());
        assertEquals("testApiKey", conf.getApiKey());
    }

    @Test
    public void testConnectionConf_poolKey() {
        ElasticsearchIO.ConnectionConf conf1 = ElasticsearchIO.ConnectionConf
            .create("http://localhost:9200", "index1")
            .withUsername("user1");
        
        ElasticsearchIO.ConnectionConf conf2 = ElasticsearchIO.ConnectionConf
            .create("http://localhost:9200", "index2")
            .withUsername("user1");
        
        String key1 = conf1.getPoolKey();
        String key2 = conf2.getPoolKey();
        
        assertNotNull(key1);
        assertNotNull(key2);
        assertNotEquals(key1, key2); // Different index should have different keys
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConnectionConf_nullAddress() {
        ElasticsearchIO.ConnectionConf.create(null, "test-index");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConnectionConf_nullIndex() {
        ElasticsearchIO.ConnectionConf.create("http://localhost:9200", null);
    }

    @Test
    public void testRetryConf_creation() {
        ElasticsearchIO.RetryConf retryConf = ElasticsearchIO.RetryConf
            .create(3, Duration.standardMinutes(5));
        
        assertEquals(3, retryConf.getMaxAttempts());
        assertEquals(Duration.standardMinutes(5), retryConf.getMaxDuration());
        assertNotNull(retryConf.getRetryPredicate());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRetryConf_invalidMaxAttempts() {
        ElasticsearchIO.RetryConf.create(0, Duration.standardMinutes(5));
    }

    @Test
    public void testAppend_defaultConfiguration() {
        ElasticsearchIO.Append append = ElasticsearchIO.append();
        
        assertEquals(1000L, append.getMaxBatchSize());
        assertEquals(5L * 1024L * 1024L, append.getMaxBatchSizeBytes());
        assertEquals(30000L, append.getFlushIntervalMillis());
        // Compression defaults ON: log JSON gzips ~8-12x
        assertTrue(append.getEnableCompression());
        assertEquals(5, append.getMaxConcurrentRequests());
    }

    @Test
    public void testAppend_customConfiguration() {
        ElasticsearchIO.Append append = ElasticsearchIO.append()
            .withConnectionConf(connectionConf)
            .withMaxBatchSize(2000L)
            .withMaxBatchSizeBytes(10L * 1024L * 1024L)
            .withFlushInterval(60000L)
            .withCompression(true)
            .withMaxConcurrentRequests(10);
        
        assertEquals(2000L, append.getMaxBatchSize());
        assertEquals(10L * 1024L * 1024L, append.getMaxBatchSizeBytes());
        assertEquals(60000L, append.getFlushIntervalMillis());
        assertTrue(append.getEnableCompression());
        assertEquals(10, append.getMaxConcurrentRequests());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAppend_invalidMaxBatchSize() {
        ElasticsearchIO.append().withMaxBatchSize(0);
    }

    @Test
    public void testDefaultRetryPredicate() {
        ElasticsearchIO.DefaultRetryPredicate predicate = 
            new ElasticsearchIO.DefaultRetryPredicate();
        
        assertNotNull(predicate);
    }

    @Test
    public void testMetrics_initialization() {
        Map<String, Long> metrics = ElasticsearchIO.getMetrics();
        
        assertNotNull(metrics);
        assertTrue(metrics.containsKey("totalDocuments"));
        assertTrue(metrics.containsKey("totalBatches"));
        assertTrue(metrics.containsKey("totalErrors"));
        assertTrue(metrics.containsKey("activeConnections"));
        
        assertEquals(Long.valueOf(0), metrics.get("totalDocuments"));
        assertEquals(Long.valueOf(0), metrics.get("totalBatches"));
        assertEquals(Long.valueOf(0), metrics.get("totalErrors"));
    }

    @Test
    public void testCleanup() {
        // This should not throw any exceptions
        ElasticsearchIO.cleanup();
        
        // Metrics should be accessible after cleanup
        Map<String, Long> metrics = ElasticsearchIO.getMetrics();
        assertNotNull(metrics);
    }

    @Test
    public void testAppendFn_initialization() {
        ElasticsearchIO.Append append = ElasticsearchIO.append()
            .withConnectionConf(connectionConf);
        
        ElasticsearchIO.Append.AppendFn appendFn = 
            new ElasticsearchIO.Append.AppendFn(append);
        
        assertNotNull(appendFn);
    }

    @Test
    public void testThreadSafety() throws InterruptedException {
        final Exception[] exceptions = new Exception[1];
        Thread[] threads = new Thread[5];
        
        for (int i = 0; i < threads.length; i++) {
            final int threadNum = i;
            threads[i] = new Thread(() -> {
                try {
                    for (int j = 0; j < 10; j++) {
                        ElasticsearchIO.ConnectionConf conf = ElasticsearchIO.ConnectionConf
                            .create("http://localhost:920" + threadNum, "index" + threadNum)
                            .withUsername("user" + threadNum);
                        
                        ElasticsearchIO.Append append = ElasticsearchIO.append()
                            .withConnectionConf(conf)
                            .withMaxBatchSize(100L + threadNum);
                        
                        assertNotNull(append);
                        assertEquals("http://localhost:920" + threadNum, conf.getAddress());
                    }
                } catch (Exception e) {
                    exceptions[0] = e;
                }
            });
        }
        
        for (Thread thread : threads) {
            thread.start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        assertNull("No exceptions should occur during concurrent access", exceptions[0]);
    }

    @Test
    public void testConfigurationChaining() {
        // Test that configuration methods can be chained properly
        ElasticsearchIO.Append append = ElasticsearchIO.append()
            .withConnectionConf(connectionConf)
            .withMaxBatchSize(1500L)
            .withMaxBatchSizeBytes(8L * 1024L * 1024L)
            .withFlushInterval(45000L)
            .withCompression(false)
            .withMaxConcurrentRequests(8);
        
        assertNotNull(append);
        assertEquals(1500L, append.getMaxBatchSize());
        assertEquals(8L * 1024L * 1024L, append.getMaxBatchSizeBytes());
        assertEquals(45000L, append.getFlushIntervalMillis());
        assertFalse(append.getEnableCompression());
        assertEquals(8, append.getMaxConcurrentRequests());
    }
}