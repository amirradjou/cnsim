package ca.yorku.cmg.cnsim.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.yorku.cmg.cnsim.engine.event.Event_ContainerArrival;
import ca.yorku.cmg.cnsim.engine.network.AbstractNetwork;
import ca.yorku.cmg.cnsim.engine.network.Partition;
import ca.yorku.cmg.cnsim.engine.node.NodeSet;
import ca.yorku.cmg.cnsim.engine.node.NodeStub;
import ca.yorku.cmg.cnsim.engine.transaction.Transaction;
import ca.yorku.cmg.cnsim.engine.transaction.TransactionGroup;

/**
 * Network partitions ({@code net.partition.*}): messages across the cut wait until it heals.
 * Lives in the engine package so it can save and restore the shared static configuration.
 */
class PartitionTest {

	private static final long START = 1_000;
	private static final long END = 9_000;

	private Properties savedProps;
	private boolean savedInitialized;

	@BeforeEach
	void saveConfig() {
		savedProps = new Properties();
		savedProps.putAll(Config.prop);
		savedInitialized = Config.initialized;
		Config.prop.remove(Partition.NODES_KEY);
		Config.prop.remove(Partition.START_KEY);
		Config.prop.remove(Partition.END_KEY);
		Config.initialized = true;
	}

	@AfterEach
	void restoreConfig() {
		Config.prop.clear();
		Config.prop.putAll(savedProps);
		Config.initialized = savedInitialized;
	}

	@Test
	void onlyMessagesAcrossTheCutDuringThePartitionWait() {
		Partition p = new Partition(List.of(1, 2), START, END);
		assertEquals(END + 50, p.arrivalTime(1, 3, 2_000, 50), "across the cut: delivered after it heals");
		assertEquals(END + 50, p.arrivalTime(3, 2, 2_000, 50), "in both directions");
		assertEquals(2_050, p.arrivalTime(1, 2, 2_000, 50), "within a side");
		assertEquals(2_050, p.arrivalTime(3, 4, 2_000, 50), "within the other side");
		assertEquals(950, p.arrivalTime(1, 3, 900, 50), "sent before the partition: already on the wire");
		assertEquals(END + 50, p.arrivalTime(1, 3, START, 50), "the start is inclusive");
		assertEquals(END + 50, p.arrivalTime(1, 3, END, 50), "the end is exclusive: sent as it heals");
		assertTrue(p.separates(2, 5));
	}

	@Test
	void rejectsEmptySidesAndBackwardWindows() {
		assertThrows(IllegalArgumentException.class, () -> new Partition(List.of(), START, END));
		assertThrows(IllegalArgumentException.class, () -> new Partition(List.of(1), END, START));
		assertThrows(IllegalArgumentException.class, () -> new Partition(List.of(1), START, START));
		assertThrows(IllegalArgumentException.class, () -> new Partition(List.of(1), -1, END));
	}

	private static void configure(String nodes, String start, String end) {
		if (nodes != null) Config.prop.setProperty(Partition.NODES_KEY, nodes);
		if (start != null) Config.prop.setProperty(Partition.START_KEY, start);
		if (end != null) Config.prop.setProperty(Partition.END_KEY, end);
	}

	@Test
	void readsTheConfiguration() {
		Set<Integer> ids = Set.of(1, 2, 3, 4);
		assertNull(Partition.fromConfig(ids), "no partition configured");
		configure("{3, 1}", "1000", "9000");
		Partition p = Partition.fromConfig(ids);
		assertEquals(Set.of(1, 3), p.getSide());
		assertEquals(START, p.getStart());
		assertEquals(END, p.getEnd());
	}

	@Test
	void explainsIncompleteOrImpossibleConfigurations() {
		Set<Integer> ids = Set.of(1, 2, 3);
		configure(null, "1000", "9000");
		assertTrue(assertThrows(IllegalArgumentException.class, () -> Partition.fromConfig(ids)).getMessage()
				.contains(Partition.NODES_KEY));
		Config.prop.remove(Partition.END_KEY);
		configure("{1}", null, null);
		assertTrue(assertThrows(IllegalArgumentException.class, () -> Partition.fromConfig(ids)).getMessage()
				.contains(Partition.END_KEY));
		configure("{1, 7}", "1000", "9000");
		assertTrue(assertThrows(IllegalArgumentException.class, () -> Partition.fromConfig(ids)).getMessage()
				.contains("node 7"));
		configure("{1, 2, 3}", "1000", "9000");
		assertTrue(assertThrows(IllegalArgumentException.class, () -> Partition.fromConfig(ids)).getMessage()
				.contains("every node"));
		configure("{1}", "soon", "9000");
		assertThrows(ConfigException.class, () -> Partition.fromConfig(ids));
	}

	/** 1 Mbps everywhere: a 1000-byte message takes 8 ms. */
	private static final class ConstantNetwork extends AbstractNetwork {
		ConstantNetwork(NodeSet nodeSet, int size) {
			super();
			this.ns = nodeSet;
			this.Net = new float[size][size];
			for (float[] row : Net) Arrays.fill(row, 1_000_000f);
		}
	}

	/** Arrival time per receiving node of the block arrivals in the queue, which it empties. */
	private static Map<Integer, Long> blockArrivals(Simulation sim) {
		Map<Integer, Long> at = new HashMap<>();
		while (!sim.getQueue().isEmpty()) {
			Event_ContainerArrival e = (Event_ContainerArrival) sim.getQueue().poll();
			at.put(e.getNode().getID(), e.getTime());
		}
		return at;
	}

	/** Times of the events in the queue, in order; empties it. */
	private static List<Long> times(Simulation sim) {
		List<Long> t = new ArrayList<>();
		while (!sim.getQueue().isEmpty()) {
			t.add(sim.getQueue().poll().getTime());
		}
		return t;
	}

	@Test
	void nodesHoldBlocksAndTransactionsForTheOtherSide() {
		Config.prop.remove(ca.yorku.cmg.cnsim.engine.node.Node.BLOCK_PROCESSING_DELAY_KEY);
		Config.prop.remove(ca.yorku.cmg.cnsim.engine.node.Node.TX_PROCESSING_DELAY_KEY);
		Simulation sim = new Simulation(1);
		NodeStub a = new NodeStub(sim), b = new NodeStub(sim), c = new NodeStub(sim);
		NodeSet ns = new NodeSet(null);
		ns.getNodes().addAll(List.of(a, b, c));
		AbstractNetwork net = new ConstantNetwork(ns, Math.max(a.getID(), Math.max(b.getID(), c.getID())) + 1);
		net.setPartition(new Partition(List.of(a.getID(), b.getID()), START, END));
		sim.setNetwork(net);

		long now = 5_000;
		Transaction tx = new Transaction(1, now, 1f, 1000f);
		TransactionGroup block = new TransactionGroup();
		block.addTransaction(tx);

		a.propagateContainer(block, now);
		assertEquals(Map.of(b.getID(), now + 8, c.getID(), END + 8), blockArrivals(sim));
		c.propagateTransaction(tx, now); // both receivers are across the cut
		assertEquals(List.of(END + 8, END + 8), times(sim));
		b.propagateTransaction(tx, now);
		assertEquals(List.of(now + 8, END + 8), times(sim));
		c.propagateContainer(block, END);
		assertEquals(Map.of(a.getID(), END + 8, b.getID(), END + 8), blockArrivals(sim), "healed");
	}
}
