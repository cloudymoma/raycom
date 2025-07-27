package bindiego.io;

import static org.junit.Assert.*;

import org.apache.beam.sdk.io.FileBasedSink.OutputFileHints;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.joda.time.DateTime;
import org.joda.time.Instant;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Simplified unit tests for {@link WindowedFilenamePolicy} class.
 * Tests basic functionality without complex mocking.
 */
@RunWith(JUnit4.class)
public class WindowedFilenamePolicyTestSimple {

    private WindowedFilenamePolicy policy;
    private static final String OUTPUT_DIRECTORY = "/tmp/test-output";
    private static final String OUTPUT_PREFIX = "data";
    private static final String SHARD_TEMPLATE = "-SSSSS-of-NNNNN";
    private static final String SUFFIX = ".json";

    // Mock objects for testing (simple implementations)
    private OutputFileHints mockOutputFileHints = new OutputFileHints() {
        @Override
        public String getMimeType() { return "application/json"; }
        
        @Override
        public String getSuggestedFilenameSuffix() { return ".json"; }
    };
    
    private PaneInfo mockPaneInfo = PaneInfo.createPane(true, true, PaneInfo.Timing.EARLY, 0, 0);

    @Before
    public void setUp() {
        policy = new WindowedFilenamePolicy(
            OUTPUT_DIRECTORY,
            OUTPUT_PREFIX,
            SHARD_TEMPLATE,
            SUFFIX
        );
    }

    @Test
    public void testConstructor_stringParameters() {
        WindowedFilenamePolicy policy = new WindowedFilenamePolicy(
            "/tmp/output",
            "prefix",
            "-SS-of-NN",
            ".txt"
        );
        
        assertNotNull(policy);
    }

    @Test
    public void testConstructor_valueProviderParameters() {
        ValueProvider<String> outputDir = ValueProvider.StaticValueProvider.of("/tmp/output");
        ValueProvider<String> prefix = ValueProvider.StaticValueProvider.of("prefix");
        ValueProvider<String> shardTemplate = ValueProvider.StaticValueProvider.of("-SS-of-NN");
        ValueProvider<String> suffix = ValueProvider.StaticValueProvider.of(".txt");
        
        WindowedFilenamePolicy policy = new WindowedFilenamePolicy(
            outputDir, prefix, shardTemplate, suffix
        );
        
        assertNotNull(policy);
    }

    @Test
    public void testWindowedFilename_basicInterval() {
        // Create an interval window for testing
        Instant start = new DateTime(2023, 1, 15, 10, 30, 0).toInstant();
        Instant end = new DateTime(2023, 1, 15, 11, 30, 0).toInstant();
        IntervalWindow window = new IntervalWindow(start, end);
        
        ResourceId result = policy.windowedFilename(
            1, 5, window, mockPaneInfo, mockOutputFileHints
        );
        
        assertNotNull(result);
        String filename = result.toString();
        assertNotNull("Filename should not be null", filename);
        assertFalse("Filename should not be empty", filename.isEmpty());
    }

    @Test
    public void testWindowedFilename_withDateTemplates() {
        // Create policy with date templates
        WindowedFilenamePolicy datePolicy = new WindowedFilenamePolicy(
            "/tmp/output/YYYY/MM/DD/HH",
            "hourly-data",
            "-SSSSS-of-NNNNN",
            ".json"
        );
        
        // Create window for January 15, 2023, 14:00-15:00
        Instant start = new DateTime(2023, 1, 15, 14, 0, 0).toInstant();
        Instant end = new DateTime(2023, 1, 15, 15, 0, 0).toInstant();
        IntervalWindow window = new IntervalWindow(start, end);
        
        ResourceId result = datePolicy.windowedFilename(
            1, 10, window, mockPaneInfo, mockOutputFileHints
        );
        
        assertNotNull(result);
        String filename = result.toString();
        assertTrue("Filename should contain year 2023", filename.contains("2023"));
        assertTrue("Filename should contain month 01", filename.contains("01"));
        assertTrue("Filename should contain day 15", filename.contains("15"));
    }

    @Test
    public void testWindowedFilename_differentDates() {
        WindowedFilenamePolicy datePolicy = new WindowedFilenamePolicy(
            "/tmp/output/YYYY/MM/DD",
            "daily",
            "-SS-of-NN",
            ".txt"
        );
        
        // Test different dates
        Instant start1 = new DateTime(2024, 2, 29, 0, 0, 0).toInstant(); // Leap year
        Instant end1 = new DateTime(2024, 2, 29, 1, 0, 0).toInstant();
        IntervalWindow window1 = new IntervalWindow(start1, end1);
        
        Instant start2 = new DateTime(2023, 12, 31, 23, 0, 0).toInstant(); // Year boundary
        Instant end2 = new DateTime(2024, 1, 1, 0, 0, 0).toInstant();
        IntervalWindow window2 = new IntervalWindow(start2, end2);
        
        ResourceId result1 = datePolicy.windowedFilename(
            0, 1, window1, mockPaneInfo, mockOutputFileHints
        );
        ResourceId result2 = datePolicy.windowedFilename(
            0, 1, window2, mockPaneInfo, mockOutputFileHints
        );
        
        assertNotNull(result1);
        assertNotNull(result2);
        
        String filename1 = result1.toString();
        String filename2 = result2.toString();
        
        assertTrue("Should handle leap year", filename1.contains("2024"));
        assertTrue("Should handle February", filename1.contains("02"));
        assertTrue("Should handle day 29", filename1.contains("29"));
        
        assertTrue("Should handle year boundary", filename2.contains("2024"));
        assertTrue("Should handle January", filename2.contains("01"));
        assertTrue("Should handle day 01", filename2.contains("01"));
    }

    @Test
    public void testWindowedFilename_multipleShards() {
        Instant start = new DateTime(2023, 1, 1, 0, 0, 0).toInstant();
        Instant end = new DateTime(2023, 1, 1, 1, 0, 0).toInstant();
        IntervalWindow window = new IntervalWindow(start, end);
        
        // Test different shard numbers
        ResourceId result1 = policy.windowedFilename(
            0, 5, window, mockPaneInfo, mockOutputFileHints
        );
        ResourceId result2 = policy.windowedFilename(
            1, 5, window, mockPaneInfo, mockOutputFileHints
        );
        ResourceId result3 = policy.windowedFilename(
            4, 5, window, mockPaneInfo, mockOutputFileHints
        );
        
        assertNotNull(result1);
        assertNotNull(result2);
        assertNotNull(result3);
        
        // All should be different
        assertNotEquals(result1.toString(), result2.toString());
        assertNotEquals(result2.toString(), result3.toString());
        assertNotEquals(result1.toString(), result3.toString());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testUnwindowedFilename_throwsException() {
        policy.unwindowedFilename(0, 1, mockOutputFileHints);
    }

    @Test
    public void testWindowedFilename_noDateTemplates() {
        WindowedFilenamePolicy simplePolicy = new WindowedFilenamePolicy(
            "/tmp/static/path",
            "simple",
            "-SS-of-NN",
            ".txt"
        );
        
        Instant start = new DateTime(2023, 6, 15, 12, 0, 0).toInstant();
        Instant end = new DateTime(2023, 6, 15, 13, 0, 0).toInstant();
        IntervalWindow window = new IntervalWindow(start, end);
        
        ResourceId result = simplePolicy.windowedFilename(
            0, 1, window, mockPaneInfo, mockOutputFileHints
        );
        
        assertNotNull(result);
        String filename = result.toString();
        assertNotNull(filename);
        assertFalse(filename.isEmpty());
    }

    @Test
    public void testWindowedFilename_performanceTest() {
        Instant start = new DateTime(2023, 1, 1, 0, 0, 0).toInstant();
        Instant end = new DateTime(2023, 1, 1, 1, 0, 0).toInstant();
        IntervalWindow window = new IntervalWindow(start, end);
        
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            ResourceId result = policy.windowedFilename(
                i, 100, window, mockPaneInfo, mockOutputFileHints
            );
            assertNotNull(result);
        }
        long endTime = System.currentTimeMillis();
        
        assertTrue("Filename generation should be fast", 
            (endTime - startTime) < 5000); // Should complete in under 5 seconds
    }

    @Test
    public void testWindowedFilename_threadSafety() throws InterruptedException {
        final Exception[] exceptions = new Exception[1];
        Thread[] threads = new Thread[3];
        
        Instant start = new DateTime(2023, 1, 1, 0, 0, 0).toInstant();
        Instant end = new DateTime(2023, 1, 1, 1, 0, 0).toInstant();
        IntervalWindow window = new IntervalWindow(start, end);
        
        for (int i = 0; i < threads.length; i++) {
            final int threadNum = i;
            threads[i] = new Thread(() -> {
                try {
                    for (int j = 0; j < 10; j++) {
                        ResourceId result = policy.windowedFilename(
                            threadNum * 10 + j, 100, window, mockPaneInfo, mockOutputFileHints
                        );
                        assertNotNull(result);
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
    public void testDateTemplateReplacement() {
        WindowedFilenamePolicy allTemplatesPolicy = new WindowedFilenamePolicy(
            "/tmp/logs/YYYY/MM/DD/HH",
            "log",
            "-SSSSS-of-NNNNN",
            ".log"
        );
        
        // Test specific date: March 7, 2023, 09:00-10:00
        Instant start = new DateTime(2023, 3, 7, 9, 0, 0).toInstant();
        Instant end = new DateTime(2023, 3, 7, 10, 0, 0).toInstant();
        IntervalWindow window = new IntervalWindow(start, end);
        
        ResourceId result = allTemplatesPolicy.windowedFilename(
            3, 20, window, mockPaneInfo, mockOutputFileHints
        );
        
        String filename = result.toString();
        assertNotNull(filename);
        // Basic validation that some date formatting occurred
        assertFalse("Should not contain literal YYYY", filename.contains("YYYY"));
        assertFalse("Should not contain literal MM", filename.contains("MM"));
        assertFalse("Should not contain literal DD", filename.contains("DD"));
        assertFalse("Should not contain literal HH", filename.contains("HH"));
    }
}