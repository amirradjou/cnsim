package ca.yorku.cmg.cnsim.bitcoin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.yorku.cmg.cnsim.engine.Config;
import ca.yorku.cmg.cnsim.engine.transaction.Transaction;

/** Which of two equally long branches a node treats as its main chain. */
class TieBreakTest {

	@BeforeEach
	void setUp() {
		Config.init("src/test/resources/application.properties"); // the block reporter reads it
	}

	private static Block block(Block parent, long txID) {
		Block b = new Block(new ArrayList<>(List.of(new Transaction(txID, 0, 10, 100))));
		b.setParent(parent);
		return b;
	}

	/** Genesis, then a block with a high ID, then a competing block with a lower ID. */
	private static Block[] fork(Blockchain chain) {
		Block genesis = block(null, 1);
		chain.addToStructure(genesis);
		Block low = block(genesis, 2);   // created first: lower block ID
		Block high = block(genesis, 3);  // created second: higher block ID
		chain.addToStructure(high);      // but received first
		chain.addToStructure(low);
		return new Block[] {low, high};
	}

	@Test
	void firstSeenKeepsTheBranchThatArrivedFirst() {
		Blockchain chain = new Blockchain(true);
		Block[] b = fork(chain);
		assertEquals(b[1].getID(), chain.getLongestTip().getID());
		assertEquals(b[1].getID(), chain.getNonOverlappingTip(block(null, 9)).getID());
		assertEquals(b[1].getID(), chain.getLongestTip().getID(), "stable after the tips are re-sorted");
	}

	@Test
	void firstSeenStillSwitchesToALongerBranch() {
		Blockchain chain = new Blockchain(true);
		Block[] b = fork(chain);
		Block longer = block(b[0], 4);
		chain.addToStructure(longer);
		assertEquals(longer.getID(), chain.getLongestTip().getID());
	}

	@Test
	void legacyRulePrefersTheHigherBlockIdOncePlaced() {
		Blockchain chain = new Blockchain();
		Block[] b = fork(chain);
		// Placing a new own block sorts the tips: the tallest with the highest ID wins.
		assertEquals(Math.max(b[0].getID(), b[1].getID()), chain.getNonOverlappingTip(block(null, 9)).getID());
	}
}
