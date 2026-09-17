package ca.yorku.cmg.cnsim.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.yorku.cmg.cnsim.engine.event.Event;
import ca.yorku.cmg.cnsim.engine.network.AbstractNetwork;
import ca.yorku.cmg.cnsim.engine.node.Node;
import ca.yorku.cmg.cnsim.engine.node.NodeSet;
import ca.yorku.cmg.cnsim.engine.node.NodeStub;
import ca.yorku.cmg.cnsim.engine.transaction.Transaction;
import ca.yorku.cmg.cnsim.engine.transaction.TransactionGroup;

/**
 * The per-byte processing delays ({@code bitcoin.blockProcessingDelayPerByte},
 * {@code bitcoin.txProcessingDelayPerByte}) are optional: a node built from a
 * configuration that does not define them must propagate with the network
 * delay only, and a node built from one that does must add size * delay.
 *
 * Lives in the engine package so it can save and restore the static
 * configuration that other tests share (surefire runs with forkCount=0).
 */
public class NodeProcessingDelayTest {

    /** 1 Mbps everywhere: a 1000-byte message takes 8 ms. */
    private static final float THROUGHPUT_BPS = 1_000_000f;
    private static final long NOW = 5_000L;

    private Properties savedProps;
    private boolean savedInitialized;

    @BeforeEach
    void saveConfig() {
        savedProps = new Properties();
        savedProps.putAll(Config.prop);
        savedInitialized = Config.initialized;
    }

    @AfterEach
    void restoreConfig() {
        Config.prop.clear();
        Config.prop.putAll(savedProps);
        Config.initialized = savedInitialized;
    }

    private static void configureDelays(String blockDelay, String txDelay) {
        Config.prop.remove(Node.BLOCK_PROCESSING_DELAY_KEY);
        Config.prop.remove(Node.TX_PROCESSING_DELAY_KEY);
        if (blockDelay != null) Config.prop.setProperty(Node.BLOCK_PROCESSING_DELAY_KEY, blockDelay);
        if (txDelay != null) Config.prop.setProperty(Node.TX_PROCESSING_DELAY_KEY, txDelay);
        Config.initialized = true;
    }

    /** Fully connected network with the same throughput on every link. */
    private static final class ConstantNetwork extends AbstractNetwork {
        ConstantNetwork(NodeSet nodeSet, int size) {
            super();
            this.ns = nodeSet;
            this.Net = new float[size][size];
            for (float[] row : Net) Arrays.fill(row, THROUGHPUT_BPS);
        }
    }

    /** Two stub nodes on a fully connected constant-throughput network. */
    private static Simulation twoNodeSimulation(NodeStub[] out) {
        Simulation sim = new Simulation(1);
        NodeStub a = new NodeStub(sim);
        NodeStub b = new NodeStub(sim);
        int size = Math.max(a.getID(), b.getID()) + 1;
        NodeSet ns = new NodeSet(null);
        ns.getNodes().add(a);
        ns.getNodes().add(b);
        sim.setNetwork(new ConstantNetwork(ns, size));
        out[0] = a;
        out[1] = b;
        return sim;
    }

    /** A one-transaction container, so its size equals the transaction's. */
    private static TransactionGroup containerOf(Transaction tx) {
        TransactionGroup g = new TransactionGroup();
        g.addTransaction(tx);
        return g;
    }

    private static long scheduledTimeOfOnlyEvent(Simulation sim) {
        assertEquals(1, sim.getQueue().size(), "one event per peer expected");
        Event e = sim.getQueue().poll();
        return e.getTime();
    }

    @Test
    void missingKeysDefaultToZeroAndDoNotAbort() {
        configureDelays(null, null);
        NodeStub[] nodes = new NodeStub[2];
        Simulation sim = twoNodeSimulation(nodes);

        assertEquals(0f, nodes[0].getBlockProcessingDelayPerByte());
        assertEquals(0f, nodes[0].getTxProcessingDelayPerByte());

        Transaction tx = new Transaction(1, NOW, 1f, 1000f);
        nodes[0].propagateTransaction(tx, NOW);
        assertEquals(NOW + 8, scheduledTimeOfOnlyEvent(sim), "network delay only");

        nodes[0].propagateContainer(containerOf(tx), NOW);
        assertEquals(NOW + 8, scheduledTimeOfOnlyEvent(sim), "network delay only");
    }

    @Test
    void configuredKeysAddSizeTimesDelay() {
        configureDelays("0.002f", "0.05f"); // ms per byte
        NodeStub[] nodes = new NodeStub[2];
        Simulation sim = twoNodeSimulation(nodes);

        assertEquals(0.002f, nodes[0].getBlockProcessingDelayPerByte());
        assertEquals(0.05f, nodes[0].getTxProcessingDelayPerByte());

        Transaction tx = new Transaction(2, NOW, 1f, 1000f);
        nodes[0].propagateTransaction(tx, NOW);
        assertEquals(NOW + 8 + 50, scheduledTimeOfOnlyEvent(sim), "network + 1000 * 0.05");

        nodes[0].propagateContainer(containerOf(tx), NOW);
        assertEquals(NOW + 8 + 2, scheduledTimeOfOnlyEvent(sim), "network + 1000 * 0.002");
    }

    @Test
    void delaysAreReadOnceAtConstruction() {
        configureDelays("0", "0");
        NodeStub[] nodes = new NodeStub[2];
        Simulation sim = twoNodeSimulation(nodes);

        // Changing the configuration afterwards must not affect an existing node.
        configureDelays("1", "1");
        Transaction tx = new Transaction(3, NOW, 1f, 1000f);
        nodes[0].propagateTransaction(tx, NOW);
        assertEquals(NOW + 8, scheduledTimeOfOnlyEvent(sim));

        NodeStub later = new NodeStub(sim);
        assertTrue(later.getTxProcessingDelayPerByte() == 1f);
    }

    @Test
    void optionalFloatGetterFallsBackOnlyWhenAbsent() {
        configureDelays(null, "0.25");
        assertEquals(7f, Config.getPropertyFloat(Node.BLOCK_PROCESSING_DELAY_KEY, 7f));
        assertEquals(0.25f, Config.getPropertyFloat(Node.TX_PROCESSING_DELAY_KEY, 7f));
    }
}
