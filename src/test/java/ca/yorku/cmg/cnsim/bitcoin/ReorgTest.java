package ca.yorku.cmg.cnsim.bitcoin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.yorku.cmg.cnsim.engine.Config;
import ca.yorku.cmg.cnsim.engine.Simulation;
import ca.yorku.cmg.cnsim.engine.transaction.Transaction;

/**
 * Which blocks leave and join the main chain on a reorg, and what happens to their transactions
 * when bitcoin.reorg.restoreTransactions is on.
 */
class ReorgTest {

	private static final String TEST_CONFIG = "src/test/resources/application.properties";

	@TempDir
	Path dir;

	@AfterEach
	void restoreDefault() throws IOException {
		initConfig(false);
	}

	private void initConfig(boolean restore) throws IOException {
		Path p = dir.resolve("config.properties");
		Files.writeString(p, Files.readString(Path.of(TEST_CONFIG))
				+ "\n" + BitcoinNode.RESTORE_ON_REORG_KEY + " = " + restore + "\n");
		Config.init(p.toString());
	}

	private static Transaction tx(long id) {
		return new Transaction(id, 0, 10, 100);
	}

	private static Block block(Block parent, Transaction... txs) {
		Block b = new Block(new ArrayList<>(List.of(txs)));
		b.setParent(parent);
		b.setHeight(parent == null ? 1 : parent.getHeight() + 1);
		return b;
	}

	private static List<Integer> ids(List<Block> blocks) {
		return blocks.stream().map(Block::getID).toList();
	}

	@Test
	void reorgListsBlocksOnEachSideOfTheFork() {
		Block g = block(null, tx(1));
		Block a1 = block(g, tx(2)), a2 = block(a1, tx(3));
		Block b1 = block(g, tx(4)), b2 = block(b1, tx(5)), b3 = block(b2, tx(6));

		Blockchain.Reorg r = Blockchain.reorg(a2, b3);
		assertEquals(List.of(a2.getID(), a1.getID()), ids(r.abandoned()));
		assertEquals(List.of(b3.getID(), b2.getID(), b1.getID()), ids(r.adopted()));

		Blockchain.Reorg extension = Blockchain.reorg(a1, a2);
		assertTrue(extension.abandoned().isEmpty());
		assertEquals(List.of(a2.getID()), ids(extension.adopted()));
	}

	@Test
	void reorgMatchesBlocksByIdAcrossCopies() throws CloneNotSupportedException {
		Block g = block(null, tx(1));
		Block a1 = block(g, tx(2));
		Block copyOfG = (Block) g.clone(); // another node's copy: same ID, different object
		Block b1 = block(copyOfG, tx(3));
		Blockchain.Reorg r = Blockchain.reorg(a1, b1);
		assertEquals(List.of(a1.getID()), ids(r.abandoned()));
		assertEquals(List.of(b1.getID()), ids(r.adopted()));
	}

	@Test
	void reorgHandlesDifferentGenesisBlocks() {
		Block g1 = block(null, tx(1)), g2 = block(null, tx(2));
		Block a = block(g1, tx(3));
		Blockchain.Reorg r = Blockchain.reorg(a, g2);
		assertEquals(List.of(a.getID(), g1.getID()), ids(r.abandoned()));
		assertEquals(List.of(g2.getID()), ids(r.adopted()));
	}

	/** Node 1 mined g -> a1 {tx2, tx3}; then learns of the longer branch b1 {tx3} -> b2 {tx5}. */
	private BitcoinNode nodeAfterReorg(Transaction doubleSpent) {
		BitcoinNode node = new BitcoinNode(new Simulation(1));
		Transaction t1 = tx(1), t2 = doubleSpent != null ? doubleSpent : tx(2), t3 = tx(3), t5 = tx(5), t6 = tx(6);
		Block g = block(null, t1);
		Block a1 = block(g, t2, t3);
		node.blockchain.addToStructure(g);
		node.blockchain.addToStructure(a1);
		node.getPool().addTransaction(t5); // heard of tx5 before its block arrived
		node.getPool().addTransaction(t6);

		Block b1 = block(g, t3);
		Block b2 = block(b1, t5);
		Block tipBefore = node.tipBeforeChange();
		node.blockchain.addToStructure(b1);
		node.getPool().extractGroup(b1);
		node.reconcilePoolAfterReorg(tipBefore);
		tipBefore = node.tipBeforeChange();
		node.blockchain.addToStructure(b2);
		node.getPool().extractGroup(b2);
		node.reconcilePoolAfterReorg(tipBefore);
		return node;
	}

	@Test
	void abandonedTransactionsReturnToThePool() throws IOException {
		initConfig(true);
		BitcoinNode node = nodeAfterReorg(null);
		assertEquals(3, node.blockchain.getLongestTip().getHeight());
		assertTrue(node.getPool().contains(2), "tx2 was only in the abandoned block: back in the pool");
		assertFalse(node.getPool().contains(3), "tx3 is on the new main chain too");
		assertFalse(node.getPool().contains(5), "tx5 is in an adopted block");
		assertTrue(node.getPool().contains(6), "unrelated pending transactions stay");
	}

	@Test
	void doubleSpentTransactionsAreNotRestored() throws IOException {
		initConfig(true);
		Transaction victim = tx(2);
		victim.markDoubleSpent();
		BitcoinNode node = nodeAfterReorg(victim);
		assertFalse(node.getPool().contains(2));
	}

	@Test
	void withoutTheFlagTransactionsOfAbandonedBlocksAreLost() throws IOException {
		initConfig(false);
		BitcoinNode node = nodeAfterReorg(null);
		assertFalse(node.getPool().contains(2), "original behaviour");
	}
}
