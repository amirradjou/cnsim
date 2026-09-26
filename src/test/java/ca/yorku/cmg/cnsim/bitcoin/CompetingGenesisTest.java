package ca.yorku.cmg.cnsim.bitcoin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.yorku.cmg.cnsim.engine.Config;
import ca.yorku.cmg.cnsim.engine.transaction.Transaction;

/**
 * Two nodes can mine the very first block at almost the same time, and with few transactions
 * around both blocks hold the same ones. A node that already has its own first block must keep
 * the other one as a competing root, or every block built on it stays an orphan there forever.
 */
class CompetingGenesisTest {

	private Transaction shared;

	@BeforeEach
	void setUp() {
		Config.init("src/test/resources/application.properties");
		shared = new Transaction(1, 0, 10, 100);
	}

	private Block block(Block parent, Transaction... txs) {
		Block b = new Block(new ArrayList<>(List.of(txs)));
		b.setParent(parent);
		b.setHeight(parent == null ? 1 : parent.getHeight() + 1);
		return b;
	}

	@Test
	void receivedGenesisBecomesASecondRootAndItsChildrenAttach() {
		Blockchain chain = new Blockchain();
		Block mine = block(null, shared);
		chain.addToStructure(mine); // this node's own first block

		Block theirs = block(null, shared); // the same transaction, mined elsewhere
		chain.addReceivedBlock(theirs);
		assertNotNull(chain.getBlockByID(theirs.getID()), "kept, not discarded for overlapping");
		assertEquals(1, theirs.getHeight());
		assertNull(theirs.getParent(), "not re-parented onto this node's block");

		Block child = block(theirs, new Transaction(2, 0, 10, 100));
		chain.addReceivedBlock(child);
		assertEquals(child.getID(), chain.getLongestTip().getID(), "the other branch is now longer");
		assertEquals(2, chain.getLongestTip().getHeight());
	}

	@Test
	void receivingTheSameGenesisTwiceAddsItOnce() {
		Blockchain chain = new Blockchain();
		Block g = block(null, shared);
		chain.addReceivedBlock(g);
		chain.addReceivedBlock(g);
		assertEquals(1, chain.printStructure().length - 1);
	}

	@Test
	void ownBlocksStillGoOnTheBestTip() {
		Blockchain chain = new Blockchain();
		Block g = block(null, shared);
		chain.addReceivedBlock(g);
		Block own = block(null, new Transaction(3, 0, 10, 100)); // parent chosen on insertion
		chain.addToStructure(own);
		assertEquals(g.getID(), own.getParent().getID());
		assertEquals(2, own.getHeight());
	}
}
