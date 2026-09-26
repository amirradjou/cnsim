package ca.yorku.cmg.cnsim.bitcoin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.yorku.cmg.cnsim.engine.Config;
import ca.yorku.cmg.cnsim.engine.Sampler;
import ca.yorku.cmg.cnsim.engine.Simulation;
import ca.yorku.cmg.cnsim.engine.StandardNodeSampler;
import ca.yorku.cmg.cnsim.engine.event.Event;
import ca.yorku.cmg.cnsim.engine.event.Event_ContainerArrival;
import ca.yorku.cmg.cnsim.engine.network.AbstractNetwork;
import ca.yorku.cmg.cnsim.engine.node.NodeSet;
import ca.yorku.cmg.cnsim.engine.transaction.Transaction;

/**
 * Drives a selfish miner through the states of Eyal and Sirer's Algorithm 1 by handing it blocks
 * directly, and checks what it withholds and publishes.
 */
class SelfishMiningBehaviorTest {

	private Simulation sim;
	private BitcoinNode selfish;
	private SelfishMiningBehavior behavior;
	private Block genesis;
	private long nextTx = 100;

	@BeforeEach
	void setUp() {
		Config.init("src/test/resources/application.properties");
		sim = new Simulation(1);
		Sampler sampler = new Sampler();
		sampler.setNodeSampler(new StandardNodeSampler(sampler, new long[] {1}, new boolean[] {false}, 1));
		sim.setSampler(sampler);

		selfish = new BitcoinNode(sim);
		behavior = new SelfishMiningBehavior(selfish);
		selfish.setBehaviorStrategy(behavior);
		selfish.setHashPower(1e6f);
		BitcoinNode honest = new BitcoinNode(sim);
		honest.setBehaviorStrategy(new HonestNodeBehavior(honest));
		honest.setHashPower(1e6f);

		NodeSet nodes = new NodeSet(null);
		nodes.getNodes().add(selfish);
		nodes.getNodes().add(honest);
		int size = Math.max(selfish.getID(), honest.getID()) + 1;
		sim.setNetwork(new AbstractNetwork() {
			{
				this.ns = nodes; // `ns` alone would be the inherited field
				this.Net = new float[size][size];
				for (float[] row : Net) Arrays.fill(row, 1e9f);
			}
		});

		genesis = block(null);
		selfish.blockchain.addToStructure(genesis);
	}

	private Transaction tx() {
		return new Transaction(nextTx++, 0, 10, 100);
	}

	private Block block(Block parent) {
		Block b = new Block(new ArrayList<>(List.of(tx())));
		b.setParent(parent);
		return b;
	}

	/** The selfish node finds a block (its content is whatever its pool holds). */
	private Block selfishMines() {
		selfish.addTransactionToPool(tx());
		selfish.reconstructMiningPool();
		Block b = new Block(selfish.getMiningPool().getTransactions());
		behavior.event_NodeCompletesValidation(b, Simulation.currTime);
		return b;
	}

	/** Another node's block reaches the selfish node. */
	private Block honestBlockArrives(Block parent) {
		Block h = block(parent);
		h.setHeight(parent.getHeight() + 1);
		behavior.event_NodeReceivesPropagatedContainer(h);
		return h;
	}

	/** Blocks the selfish node has sent to the network so far. */
	private List<Integer> broadcast() {
		List<Integer> ids = new ArrayList<>();
		for (Event e : sim.getQueue()) {
			if (e instanceof Event_ContainerArrival a) ids.add(a.getContainer().getID());
		}
		return ids;
	}

	private int tipHeight() {
		return selfish.blockchain.getLongestTip().getHeight();
	}

	@Test
	void withholdsItsFirstBlock() {
		Block s1 = selfishMines();
		assertEquals(1, behavior.withheldCount());
		assertEquals(1, tipHeight(), "the public chain does not know the block");
		assertFalse(broadcast().contains(s1.getID()));
	}

	@Test
	void leadOfOneBecomesARaceAndTheNextBlockWinsIt() {
		Block s1 = selfishMines();
		honestBlockArrives(genesis); // lead was 1: publish and race
		assertEquals(0, behavior.withheldCount());
		assertTrue(broadcast().contains(s1.getID()));

		Block s2 = selfishMines(); // finds the next block while racing: publish at once
		assertEquals(0, behavior.withheldCount());
		assertTrue(broadcast().contains(s2.getID()));
		assertEquals(3, tipHeight());
		assertEquals(s2.getID(), selfish.blockchain.getLongestTip().getID());
	}

	@Test
	void losingTheRaceMeansMiningOnTheHonestChain() {
		selfishMines();
		Block h1 = honestBlockArrives(genesis);
		Block h2 = honestBlockArrives(h1); // honest miners extended their own block
		assertEquals(h2.getID(), selfish.blockchain.getLongestTip().getID());
		Block s = selfishMines();
		assertEquals(h2.getID(), s.getParent().getID(), "the next private block builds on the honest tip");
	}

	@Test
	void leadOfTwoPublishesEverythingAndWins() {
		selfishMines();
		Block s2 = selfishMines();
		assertEquals(2, behavior.withheldCount());
		honestBlockArrives(genesis);
		assertEquals(0, behavior.withheldCount());
		assertEquals(s2.getID(), selfish.blockchain.getLongestTip().getID());
		assertEquals(3, tipHeight());
	}

	@Test
	void largerLeadPublishesJustEnoughToMatch() {
		Block s1 = selfishMines();
		Block s2 = selfishMines();
		Block s3 = selfishMines();
		Block h1 = honestBlockArrives(genesis); // lead 3: publish one block to match height 2
		assertEquals(2, behavior.withheldCount());
		assertTrue(broadcast().contains(s1.getID()));
		assertFalse(broadcast().contains(s2.getID()));

		honestBlockArrives(h1); // lead 2: publish the rest and win
		assertEquals(0, behavior.withheldCount());
		assertTrue(broadcast().contains(s3.getID()));
		assertEquals(s3.getID(), selfish.blockchain.getLongestTip().getID());
	}
}
