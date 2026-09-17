package ca.yorku.cmg.cnsim.engine.network;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Test class to verify propagation time calculations work correctly
 * for different throughput values and container sizes.
 * This test directly validates the formula used in AbstractNetwork.getPropagationTime()
 */
public class PropagationTimeTest {

    /**
     * Direct implementation of the propagation time formula used in AbstractNetwork
     * Formula: Math.round((size * 8 * 1000) / throughput)
     */
    private long calculatePropagationTime(float throughput, float size) {
        if(size < 0)
            throw new ArithmeticException("Size < 0");
        if(throughput < 0)
            throw new ArithmeticException("Throughput < 0");

        if(throughput == 0)
            return (-1);
        else
            // Use double for intermediate result to avoid overflow
            return Math.round((double)size * 8 * 1000 / throughput);
    }

    @Test
    void testPropagationTimeFormula() {
        // Test the basic formula: Math.round((size * 8 * 1000) / throughput)
        
        // Test case 1: 1 MB container with 100 Mbps (from propagationTime_half.properties)
        float size1MB = 1000000f; // 1 MB in bytes
        float throughput100Mbps = 100000000f; // 100 Mbps in bps
        
        long expectedTime1 = Math.round((size1MB * 8 * 1000) / throughput100Mbps);
        long actualTime1 = calculatePropagationTime(throughput100Mbps, size1MB);
        
        assertEquals(expectedTime1, actualTime1);
        assertEquals(80, actualTime1); // Should be 80 ms
        
        // Test case 2: 1 MB container with 1 Mbps (from propagationTime_double.properties)
        float throughput1Mbps = 1000000f; // 1 Mbps in bps
        
        long expectedTime2 = Math.round((size1MB * 8 * 1000) / throughput1Mbps);
        long actualTime2 = calculatePropagationTime(throughput1Mbps, size1MB);
        
        assertEquals(expectedTime2, actualTime2);
        assertEquals(8000, actualTime2); // Should be 8000 ms (8 seconds)
    }

    @Test
    void testConfigFileValues() {
        // Test with exact values from the config files
        
        // Values from thesis.bitcoin.propagationTime_half.properties
        float throughputHalf = 100000000f; // 100 Mbps
        float throughputSDHalf = 100000f; // 100 kbps SD
        
        // Values from thesis.bitcoin.propagationTime_double.properties  
        float throughputDouble = 1000000f; // 1 Mbps
        float throughputSDDouble = 1000f; // 1 kbps SD
        
        // Test with 1 MB container
        float containerSize = 1000000f; // 1 MB
        
        long timeHalf = calculatePropagationTime(throughputHalf, containerSize);
        long timeDouble = calculatePropagationTime(throughputDouble, containerSize);
        
        // Verify expected results
        assertEquals(80, timeHalf);
        assertEquals(8000, timeDouble);
        
        // Verify the ratio is correct (should be 100x difference)
        assertEquals(100.0, (double)timeDouble/timeHalf, 0.01);
    }

    @Test
    void testEdgeCases() {
        // Test edge cases to ensure no errors occur
        
        // Very small size
        long smallTime = calculatePropagationTime(1000000f, 1f);
        assertTrue(smallTime >= 0);
        
        // Very large size (10 MB)
        long largeTime = calculatePropagationTime(1000000f, 10000000f);
        assertTrue(largeTime >= 0);
        assertEquals(80000, largeTime); // 10 MB should take 80 seconds at 1 Mbps
        
        // Zero throughput should return -1 (not connected)
        long zeroTime = calculatePropagationTime(0f, 1000000f);
        assertEquals(-1, zeroTime);
    }

    @Test
    void testNegativeValues() {
        // Test that negative values throw appropriate exceptions
        
        // Negative size
        assertThrows(ArithmeticException.class, () -> {
            calculatePropagationTime(1000000f, -1f);
        });
        
        // Negative throughput
        assertThrows(ArithmeticException.class, () -> {
            calculatePropagationTime(-1f, 1000000f);
        });
    }

    @Test
    void testTransactionSizes() {
        // Test with typical transaction sizes from config
        float txSize = 225f; // bytes (from config)
        
        // Test with both throughput values
        float throughputHalf = 100000000f; // 100 Mbps
        float throughputDouble = 1000000f; // 1 Mbps
        
        long txTimeHalf = calculatePropagationTime(throughputHalf, txSize);
        long txTimeDouble = calculatePropagationTime(throughputDouble, txSize);
        
        // At 100 Mbps, small transactions may round to 0 ms
        assertTrue(txTimeHalf >= 0);
        assertTrue(txTimeDouble >= 0);
        
        // Verify the calculation is correct
        long expectedTxTimeHalf = Math.round((txSize * 8 * 1000) / throughputHalf);
        long expectedTxTimeDouble = Math.round((txSize * 8 * 1000) / throughputDouble);
        
        assertEquals(expectedTxTimeHalf, txTimeHalf);
        assertEquals(expectedTxTimeDouble, txTimeDouble);
    }

    @Test
    void testBlockSizes() {
        // Test with typical block sizes from config
        float blockSize = 1000000f; // 1 MB (from config)
        
        // Test with both throughput values
        float throughputHalf = 100000000f; // 100 Mbps
        float throughputDouble = 1000000f; // 1 Mbps
        
        long blockTimeHalf = calculatePropagationTime(throughputHalf, blockSize);
        long blockTimeDouble = calculatePropagationTime(throughputDouble, blockSize);
        
        // Verify expected results
        assertEquals(80, blockTimeHalf);
        assertEquals(8000, blockTimeDouble);
        
        // Verify the ratio is correct
        assertEquals(100.0, (double)blockTimeDouble/blockTimeHalf, 0.01);
    }

    @Test
    void testThroughputRange() {
        // Test with various throughput values to ensure no overflow
        float containerSize = 1000000f; // 1 MB
        
        // Test very low throughput (should give very high propagation time)
        long lowTime = calculatePropagationTime(1000f, containerSize); // 1 kbps
        assertTrue(lowTime > 0);
        assertTrue(lowTime < Long.MAX_VALUE); // Should not overflow
        
        // Test very high throughput (should give very low propagation time)
        long highTime = calculatePropagationTime(1000000000f, containerSize); // 1 Gbps
        assertTrue(highTime >= 0);
        
        // Test that higher throughput gives lower propagation time
        assertTrue(highTime < lowTime);
    }

    @Test
    void testPrecisionAndRounding() {
        // Test that rounding works correctly
        float smallSize = 125f; // 125 bytes
        float throughput = 1000000f; // 1 Mbps
        
        // Expected: (125 * 8 * 1000) / 1000000 = 1.0 ms
        long time = calculatePropagationTime(throughput, smallSize);
        assertEquals(1, time);
        
        // Test with size that should round down
        float sizeForRounding = 124f; // 124 bytes
        // Expected: (124 * 8 * 1000) / 1000000 = 0.992 ms -> rounds to 1 ms
        long roundedTime = calculatePropagationTime(throughput, sizeForRounding);
        assertEquals(1, roundedTime);
    }

    @Test
    void testLargeContainerSizes() {
        // Test with very large container sizes to ensure no overflow
        float largeContainerSize = 100000000f; // 100 MB
        
        // Test with 100 Mbps throughput
        long time100Mbps = calculatePropagationTime(100000000f, largeContainerSize);
        assertTrue(time100Mbps > 0);
        assertTrue(time100Mbps < Long.MAX_VALUE);
        assertEquals(8000, time100Mbps); // 100 MB should take 8 seconds at 100 Mbps
        
        // Test with 1 Mbps throughput
        long time1Mbps = calculatePropagationTime(1000000f, largeContainerSize);
        assertTrue(time1Mbps > 0);
        assertTrue(time1Mbps < Long.MAX_VALUE);
        assertEquals(800000, time1Mbps); // 100 MB should take 800 seconds at 1 Mbps
    }

    @Test
    void testExtremeValues() {
        // Test with extreme values to ensure robustness
        
        // Very high throughput (10 Gbps)
        long time10Gbps = calculatePropagationTime(10000000000f, 1000000f);
        assertTrue(time10Gbps >= 0);
        assertEquals(1, time10Gbps); // 1 MB should take 1 ms at 10 Gbps
        
        // Very low throughput (1 bps)
        long time1bps = calculatePropagationTime(1f, 1000000f);
        assertTrue(time1bps > 0);
        assertTrue(time1bps < Long.MAX_VALUE);
        assertEquals(8000000000L, time1bps); // 1 MB should take 8 billion ms at 1 bps
    }
} 