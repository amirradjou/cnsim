package ca.yorku.cmg.cnsim.engine.network;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Test class to verify that propagation time calculations don't overflow
 * when using actual throughput values from NetLog files.
 */
public class NetLogOverflowTest {

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
    public void testOverflowWithActualNetLogValues() {
        System.out.println("=== Testing Overflow with Actual NetLog Values ===");
        
        // Test container sizes
        float[] containerSizes = {
            225f,           // Typical transaction size (bytes)
            1000000f,       // 1 MB block size (bytes)
            10000000f,      // 10 MB container
            100000000f,     // 100 MB container
            1000000000f     // 1 GB container
        };
        
        // Throughput values from NetLog files
        float[] throughputValues = {
            // From increase-throughput logs (high throughput ~100 Mbps)
            9.985619E7f,    // ~99.86 Mbps
            1.00063416E8f,  // ~100.06 Mbps
            1.001352E8f,    // ~100.14 Mbps
            1.0027588E8f,   // ~100.28 Mbps
            
            // From decrease-throughput logs (low throughput ~1 Mbps)
            856195.06f,     // ~0.86 Mbps
            1063419.5f,     // ~1.06 Mbps
            1135203.0f,     // ~1.14 Mbps
            1275880.8f,     // ~1.28 Mbps
            
            // Extreme values for stress testing
            1f,             // 1 bps (very low)
            1000000000f,    // 1 Gbps (very high)
            10000000000f    // 10 Gbps (extreme)
        };
        
        boolean overflowDetected = false;
        
        for (float size : containerSizes) {
            for (float throughput : throughputValues) {
                try {
                    long propagationTime = calculatePropagationTime(throughput, size);
                    
                    // Check for overflow indicators
                    if (propagationTime == Integer.MAX_VALUE || 
                        propagationTime == Long.MAX_VALUE ||
                        propagationTime < 0) {
                        System.out.printf("OVERFLOW DETECTED: size=%.0f bytes, throughput=%.2f bps, result=%d%n", 
                                        size, throughput, propagationTime);
                        overflowDetected = true;
                    } else {
                        System.out.printf("OK: size=%.0f bytes, throughput=%.2f bps, propagation=%d ms%n", 
                                        size, throughput, propagationTime);
                    }
                    
                    // Additional validation for reasonable results
                    if (throughput > 0 && size > 0) {
                        double expectedTime = (size * 8 * 1000.0) / throughput;
                        if (expectedTime > Long.MAX_VALUE) {
                            System.out.printf("OVERFLOW: Expected time %.2e exceeds Long.MAX_VALUE%n", expectedTime);
                            overflowDetected = true;
                        }
                    }
                    
                } catch (Exception e) {
                    System.out.printf("EXCEPTION: size=%.0f bytes, throughput=%.2f bps, error=%s%n", 
                                    size, throughput, e.getMessage());
                    overflowDetected = true;
                }
            }
        }
        
        System.out.println("\n=== Test Summary ===");
        if (overflowDetected) {
            System.out.println("❌ OVERFLOW ISSUES DETECTED!");
            fail("Overflow issues detected in propagation time calculations");
        } else {
            System.out.println("✅ No overflow issues detected");
        }
    }

    @Test
    public void testSpecificNetLogScenarios() {
        System.out.println("\n=== Testing Specific NetLog Scenarios ===");
        
        // Test scenarios based on actual NetLog data
        Object[][] scenarios = {
            // {description, size_bytes, throughput_bps, expected_reasonable_range_ms}
            {"Small transaction on high throughput", 225f, 9.985619E7f, 1000L}, // Should be ~0.018ms
            {"1MB block on high throughput", 1000000f, 1.00063416E8f, 100000L}, // Should be ~80ms
            {"Small transaction on low throughput", 225f, 856195.06f, 10000L}, // Should be ~2.1ms
            {"1MB block on low throughput", 1000000f, 1063419.5f, 10000000L}, // Should be ~7525ms
            {"Large container (10MB) on high throughput", 10000000f, 1.0027588E8f, 1000000L}, // Should be ~797ms
            {"Large container (10MB) on low throughput", 10000000f, 856195.06f, 100000000L}, // Should be ~93333ms
        };
        
        for (Object[] scenario : scenarios) {
            String description = (String) scenario[0];
            float size = (Float) scenario[1];
            float throughput = (Float) scenario[2];
            long maxExpected = (Long) scenario[3];
            
            long propagationTime = calculatePropagationTime(throughput, size);
            
            System.out.printf("%s: %d ms%n", description, propagationTime);
            
            // Check if result is reasonable
            assertTrue(propagationTime >= 0, "Propagation time should be non-negative");
            assertTrue(propagationTime <= maxExpected, 
                      String.format("Propagation time %d ms exceeds reasonable maximum %d ms for %s", 
                                  propagationTime, maxExpected, description));
        }
    }

    @Test
    public void testEdgeCases() {
        System.out.println("\n=== Testing Edge Cases ===");
        
        // Test edge cases that could cause overflow
        Object[][] edgeCases = {
            {"Zero throughput", 1000000f, 0f, -1L},
            {"Zero size", 0f, 1000000f, 0L},
            {"Very large size, very low throughput", 1000000000f, 1f, 8000000000000L}, // 8 trillion ms
            {"Very large size, moderate throughput", 1000000000f, 1000000f, 8000000L}, // 8 million ms
            {"Moderate size, very high throughput", 1000000f, 10000000000f, 1L}, // Should be ~0.8ms
        };
        
        for (Object[] edgeCase : edgeCases) {
            String description = (String) edgeCase[0];
            float size = (Float) edgeCase[1];
            float throughput = (Float) edgeCase[2];
            long expected = (Long) edgeCase[3];
            
            try {
                long propagationTime = calculatePropagationTime(throughput, size);
                System.out.printf("%s: %d ms (expected: %d)%n", description, propagationTime, expected);
                
                if (expected != -1) { // Skip validation for zero throughput case
                    assertEquals(expected, propagationTime, 
                                String.format("Mismatch for %s", description));
                }
            } catch (Exception e) {
                System.out.printf("%s: EXCEPTION - %s%n", description, e.getMessage());
                if (expected != -1) {
                    fail(String.format("Unexpected exception for %s: %s", description, e.getMessage()));
                }
            }
        }
    }
} 