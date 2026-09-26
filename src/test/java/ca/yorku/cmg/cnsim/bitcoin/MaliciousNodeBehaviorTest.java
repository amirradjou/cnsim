package ca.yorku.cmg.cnsim.bitcoin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ca.yorku.cmg.cnsim.engine.Config;
import ca.yorku.cmg.cnsim.engine.Simulation;
import ca.yorku.cmg.cnsim.engine.transaction.Transaction;

class MaliciousNodeBehaviorTest {

    private static final String TEST_CONFIG = "src/test/resources/application.properties";

    private MaliciousNodeBehavior maliciousNode;
    private BitcoinNode mockNode;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        // BitcoinNode reads bitcoin.* and pow.* keys in its constructor; load them here
        // rather than relying on an earlier test class having initialized Config.
        Config.init(TEST_CONFIG);
        mockNode = new BitcoinNode(new Simulation(1));
        maliciousNode = new MaliciousNodeBehavior(mockNode);
    }

    @AfterEach
    void resetConfig() {
        Config.init(TEST_CONFIG);
    }

    @Test
    void testTargetTransactionExclusionAfterAttackCompletion() throws Exception {
        int targetTxID = 100;
        maliciousNode.setTargetTransaction(targetTxID);
        Transaction targetTransaction = new Transaction(targetTxID, 1000, 50, 100);

        // Simulate attack completion by setting the flag directly
        // (in a real run revealHiddenChain() sets it).
        java.lang.reflect.Field field = MaliciousNodeBehavior.class.getDeclaredField("isAttackCompleted");
        field.setAccessible(true);
        field.set(maliciousNode, true);

        // After the attack the target must be dropped on receipt, whether it
        // arrives from a client or via propagation: neither call may reach the
        // honest path (which would need a network) nor touch the pool.
        maliciousNode.event_NodeReceivesClientTransaction(targetTransaction, 1000);
        maliciousNode.event_NodeReceivesPropagatedTransaction(targetTransaction, 2000);

        assertFalse(mockNode.getPool().contains(targetTransaction),
                "Target transaction must not enter the pool after attack completion");
        assertEquals(0, mockNode.getPool().getCount(),
                "Pool must stay empty after the target is ignored");
    }

    /** The reveal rule with the thesis thresholds (min 2, max 15). */
    @ParameterizedTest(name = "hidden {0}, public growth {1} -> reveal {2}")
    @CsvSource({
            "0, 0, false",
            "3, 2, false",   // longer, but only 2 confirmations: not past min
            "3, 3, false",   // not longer than the public growth
            "4, 3, true",    // longer and 3 > min confirmations
            "10, 9, true",
            "9, 9, false",
            "1, 15, false",  // at max, not beyond
            "1, 16, true",   // beyond max: reveal regardless
    })
    void revealRule(int hidden, int growth, boolean reveal) {
        assertEquals(reveal, MaliciousNodeBehavior.shouldReveal(hidden, growth,
                MaliciousNodeBehavior.DEFAULT_MIN_CHAIN_LENGTH, MaliciousNodeBehavior.DEFAULT_MAX_CHAIN_LENGTH));
    }

    @ParameterizedTest(name = "hidden {0}, public growth {1} -> abandon {2}")
    @CsvSource({
            "14, 14, false", // below max
            "15, 15, true",  // at max and not longer
            "10, 15, true",
            "16, 15, false", // longer: the reveal rule fires instead
    })
    void abandonRule(int hidden, int growth, boolean abandon) {
        assertEquals(abandon, MaliciousNodeBehavior.shouldAbandon(hidden, growth,
                MaliciousNodeBehavior.DEFAULT_MAX_CHAIN_LENGTH));
    }

    @Test
    void thresholdsDefaultToTheThesisValues() {
        assertEquals(2, MaliciousNodeBehavior.DEFAULT_MIN_CHAIN_LENGTH);
        assertEquals(15, MaliciousNodeBehavior.DEFAULT_MAX_CHAIN_LENGTH);
    }

    @Test
    void thresholdsMustBeOrdered() throws IOException {
        Path p = dir.resolve("config.properties");
        Files.writeString(p, Files.readString(Path.of(TEST_CONFIG))
                + "\n" + MaliciousNodeBehavior.MIN_CHAIN_LENGTH_KEY + " = 6\n"
                + MaliciousNodeBehavior.MAX_CHAIN_LENGTH_KEY + " = 3\n");
        Config.init(p.toString());
        assertThrows(IllegalArgumentException.class, () -> new MaliciousNodeBehavior(mockNode));
    }
}
