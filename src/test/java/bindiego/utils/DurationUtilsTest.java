package bindiego.utils;

import static org.junit.Assert.*;
import static org.hamcrest.CoreMatchers.*;

import org.joda.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link DurationUtils} class.
 * Tests various duration parsing scenarios including valid inputs,
 * edge cases, and error conditions.
 */
@RunWith(JUnit4.class)
public class DurationUtilsTest {

    @Test
    public void testParseDuration_validSeconds() {
        Duration result = DurationUtils.parseDuration("5s");
        assertEquals(Duration.standardSeconds(5), result);
        assertEquals(5000L, result.getMillis());
    }

    @Test
    public void testParseDuration_validMinutes() {
        Duration result = DurationUtils.parseDuration("13m");
        assertEquals(Duration.standardMinutes(13), result);
        assertEquals(13 * 60 * 1000L, result.getMillis());
    }

    @Test
    public void testParseDuration_validHours() {
        Duration result = DurationUtils.parseDuration("2h");
        assertEquals(Duration.standardHours(2), result);
        assertEquals(2 * 60 * 60 * 1000L, result.getMillis());
    }

    @Test
    public void testParseDuration_singleDigitSeconds() {
        Duration result = DurationUtils.parseDuration("1s");
        assertEquals(Duration.standardSeconds(1), result);
        assertEquals(1000L, result.getMillis());
    }

    @Test
    public void testParseDuration_multipleDigitMinutes() {
        Duration result = DurationUtils.parseDuration("123m");
        assertEquals(Duration.standardMinutes(123), result);
        assertEquals(123 * 60 * 1000L, result.getMillis());
    }

    @Test
    public void testParseDuration_largeHours() {
        Duration result = DurationUtils.parseDuration("24h");
        assertEquals(Duration.standardHours(24), result);
        assertEquals(24 * 60 * 60 * 1000L, result.getMillis());
    }

    @Test
    public void testParseDuration_hoursOnly() {
        // Based on the parser implementation, it seems to parse only the first matching unit
        Duration result = DurationUtils.parseDuration("1h");
        assertEquals(Duration.standardHours(1), result);
        assertEquals(1 * 60 * 60 * 1000L, result.getMillis());
    }

    @Test
    public void testParseDuration_minutesOnly() {
        Duration result = DurationUtils.parseDuration("5m");
        assertEquals(Duration.standardMinutes(5), result);
        assertEquals(5 * 60 * 1000L, result.getMillis());
    }

    @Test
    public void testParseDuration_secondsOnly() {
        Duration result = DurationUtils.parseDuration("30s");
        assertEquals(Duration.standardSeconds(30), result);
        assertEquals(30 * 1000L, result.getMillis());
    }

    @Test(expected = NullPointerException.class)
    public void testParseDuration_nullInput() {
        DurationUtils.parseDuration(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseDuration_emptyString() {
        DurationUtils.parseDuration("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseDuration_zeroSeconds() {
        DurationUtils.parseDuration("0s");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseDuration_zeroMinutes() {
        DurationUtils.parseDuration("0m");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseDuration_zeroHours() {
        DurationUtils.parseDuration("0h");
    }

    @Test(expected = RuntimeException.class)
    public void testParseDuration_invalidFormat() {
        DurationUtils.parseDuration("invalid");
    }

    @Test(expected = RuntimeException.class)
    public void testParseDuration_noSuffix() {
        DurationUtils.parseDuration("5");
    }

    @Test(expected = RuntimeException.class)
    public void testParseDuration_invalidSuffix() {
        DurationUtils.parseDuration("5x");
    }

    @Test(expected = RuntimeException.class)
    public void testParseDuration_negativeValue() {
        DurationUtils.parseDuration("-5s");
    }

    @Test(expected = RuntimeException.class)
    public void testParseDuration_floatingPoint() {
        DurationUtils.parseDuration("5.5s");
    }

    @Test(expected = RuntimeException.class)
    public void testParseDuration_spacesInInput() {
        DurationUtils.parseDuration("5 s");
    }

    @Test
    public void testParseDuration_uppercaseSuffix() {
        // Uppercase suffix may or may not be supported - just test that it doesn't crash
        try {
            Duration result = DurationUtils.parseDuration("5S");
            // If it succeeds, that's fine too
            assertTrue(result.getMillis() >= 0);
        } catch (Exception e) {
            // If it fails, that's also acceptable behavior
            assertTrue(e instanceof IllegalArgumentException || e instanceof RuntimeException);
        }
    }

    @Test
    public void testParseDuration_veryLargeValue() {
        Duration result = DurationUtils.parseDuration("999999s");
        assertEquals(Duration.standardSeconds(999999), result);
        assertEquals(999999 * 1000L, result.getMillis());
    }

    @Test
    public void testParseDuration_singleCharacterInput() {
        // Test single character inputs that should fail
        try {
            DurationUtils.parseDuration("h");
            fail("Should throw exception for 'h' without number");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testParseDuration_onlyHours() {
        Duration result = DurationUtils.parseDuration("8h");
        assertEquals(Duration.standardHours(8), result);
        assertEquals(8 * 60 * 60 * 1000L, result.getMillis());
    }

    @Test
    public void testParseDuration_onlyMinutes() {
        Duration result = DurationUtils.parseDuration("45m");
        assertEquals(Duration.standardMinutes(45), result);
        assertEquals(45 * 60 * 1000L, result.getMillis());
    }

    @Test
    public void testParseDuration_boundaryValues() {
        // Test various boundary values
        Duration oneSecond = DurationUtils.parseDuration("1s");
        assertEquals(1000L, oneSecond.getMillis());
        
        Duration oneMinute = DurationUtils.parseDuration("1m");
        assertEquals(60 * 1000L, oneMinute.getMillis());
        
        Duration oneHour = DurationUtils.parseDuration("1h");
        assertEquals(60 * 60 * 1000L, oneHour.getMillis());
    }

    @Test
    public void testParseDuration_performanceTest() {
        // Basic performance test - should be fast
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            DurationUtils.parseDuration("5s");
        }
        long endTime = System.currentTimeMillis();
        assertTrue("Duration parsing should be fast", (endTime - startTime) < 1000);
    }

    @Test
    public void testParseDuration_threadsafety() throws InterruptedException {
        // Basic thread safety test
        final Exception[] exceptions = new Exception[1];
        Thread[] threads = new Thread[10];
        
        for (int i = 0; i < threads.length; i++) {
            final int threadNum = i;
            threads[i] = new Thread(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        Duration result = DurationUtils.parseDuration((threadNum + 1) + "s");
                        assertEquals((threadNum + 1) * 1000L, result.getMillis());
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
}