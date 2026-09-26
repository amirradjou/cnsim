package ca.yorku.cmg.cnsim.bitcoin;

import java.util.ArrayList;
import java.util.List;

import ca.yorku.cmg.cnsim.engine.Simulation;
import ca.yorku.cmg.cnsim.engine.transaction.ITxContainer;
import ca.yorku.cmg.cnsim.engine.transaction.Transaction;

/**
 * Selfish mining (Eyal and Sirer, "Majority is not enough", 2014, Algorithm 1). The node keeps
 * the blocks it mines on a private branch and publishes them strategically, so that honest
 * miners waste work on blocks that end up stale:
 * <ul>
 * <li>It mines on its private branch if it has one, otherwise on the public tip.</li>
 * <li>When another node's block extends the public chain, with {@code d} the private lead
 * before that block: d = 0, adopt the public chain; d = 1, publish the private block and race;
 * d = 2, publish everything (the private chain wins by one); d &gt; 2, publish just enough to
 * match the public height and stay ahead.</li>
 * <li>When it mines a block while racing (a published branch of one block tied with an honest
 * block), it publishes at once and wins.</li>
 * </ul>
 * The share of honest miners that build on the selfish block during a race (Eyal and Sirer's
 * gamma) is not a parameter here: it follows from which competing block each node receives
 * first, so it depends on the network and on the tie-break rule (see
 * {@code consensus.tieBreak = first-seen}).
 * <p>
 * Transactions are handled as by an honest node, except that the pool never holds transactions
 * of the private branch (reorg handling could otherwise hand back those of published branch
 * blocks while the node still mines on top of them). When the branch is abandoned its
 * transactions that are not on the new main chain go back to the pool.
 *
 * @author Amirreza Radjou
 */
public class SelfishMiningBehavior implements NodeBehaviorStrategy {

	private final BitcoinNode node;
	private final HonestNodeBehavior honest;

	/** Blocks mined since the private branch forked from the public chain, oldest first. */
	private final List<Block> branch = new ArrayList<>();
	/** How many blocks at the start of {@link #branch} have been published. */
	private int published;

	public SelfishMiningBehavior(BitcoinNode node) {
		this.node = node;
		this.honest = new HonestNodeBehavior(node);
	}

	@Override
	public void event_NodeReceivesClientTransaction(Transaction t, long time) {
		honest.event_NodeReceivesClientTransaction(t, time);
	}

	@Override
	public void event_NodeReceivesPropagatedTransaction(Transaction t, long time) {
		honest.event_NodeReceivesPropagatedTransaction(t, time);
	}

	@Override
	public void event_NodeCompletesValidation(ITxContainer t, long time) {
		Block b = (Block) t;
		b.validateBlock(node.miningPool, Simulation.currTime, System.currentTimeMillis() - Simulation.sysStartTime,
				node.getID(), "Node Completes Validation", node.getOperatingDifficulty(), node.getProspectiveCycles());
		node.completeValidation(node.miningPool, time);
		report(b, "Node Completes Validation");

		if (!branch.isEmpty() && privateHeight() < publicHeight()) {
			abandonBranch(); // overtaken without noticing; cannot normally happen
		}
		int leadBefore = privateHeight() - publicHeight();
		Block parent = branch.isEmpty() ? node.blockchain.getLongestTip() : branch.get(branch.size() - 1);
		if (parent != null && node.blockchain.hasChainOverlap(b, parent)) {
			BitcoinReporter.addErrorEntry("SelfishMiningBehavior: block " + b.getID()
					+ " repeats a transaction of its own chain; it will be rejected by every node.");
		}
		b.setParent(parent);
		b.setHeight(parent == null ? 1 : parent.getHeight() + 1);
		branch.add(b);
		report(b, "Selfish: block withheld");

		if (leadBefore == 0 && branch.size() == 2) {
			// We were racing with one published block; the new block decides the race.
			publishThrough(Integer.MAX_VALUE);
			clearBranch();
		}
		honest.processPostValidationActivities(time);
	}

	@Override
	public void event_NodeReceivesPropagatedContainer(ITxContainer t) {
		Block b = (Block) t;
		b.setCurrentNodeID(node.getID());
		b.setLastBlockEvent("Node Receives Propagated Block");
		b.setValidationCycles(-1.0);
		b.setValidationDifficulty(-1.0);
		report(b, b.getLastBlockEvent());

		if (node.blockchain.contains(b)) {
			BitcoinReporter.addErrorEntry("SelfishMiningBehavior: propagated block " + b.getID() + " overlaps with its own chain.");
			return;
		}
		int publicBefore = publicHeight();
		int leadBefore = privateHeight() - publicBefore;
		honest.handleNewBlockReception(b);
		excludeBranchFromPool();
		int publicAfter = publicHeight();
		if (publicAfter <= publicBefore) {
			return; // a stale or orphan block: the public chain did not grow
		}
		if (branch.isEmpty() || leadBefore <= 0) {
			abandonBranch(); // they win (or the race is decided): mine on the public chain
		} else if (leadBefore == 1) {
			publishThrough(Integer.MAX_VALUE); // same length now: race
		} else if (leadBefore == 2) {
			publishThrough(Integer.MAX_VALUE); // one block ahead: publishing wins
			clearBranch();
		} else {
			publishThrough(publicAfter); // comfortably ahead: match the public height
		}
	}

	private int publicHeight() {
		Block tip = node.blockchain.getLongestTip();
		return tip == null ? 0 : tip.getHeight();
	}

	private int privateHeight() {
		return branch.isEmpty() ? publicHeight() : branch.get(branch.size() - 1).getHeight();
	}

	/** Publishes, in order, the unpublished branch blocks with height at most {@code maxHeight}. */
	private void publishThrough(int maxHeight) {
		while (published < branch.size() && branch.get(published).getHeight() <= maxHeight) {
			Block p = branch.get(published++);
			Block tipBefore = node.tipBeforeChange();
			node.blockchain.addReceivedBlock(p);
			node.reconcilePoolAfterReorg(tipBefore);
			excludeBranchFromPool();
			report(p, "Selfish: block published");
			try {
				node.propagateContainer((ITxContainer) p.clone(), Simulation.currTime);
			} catch (CloneNotSupportedException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	/**
	 * Removes the private branch's transactions from the pool (and the mining pool), so that new
	 * private blocks never repeat a transaction of their own chain.
	 */
	private void excludeBranchFromPool() {
		if (branch.isEmpty()) return;
		int before = node.getPool().getCount();
		for (Block p : branch) {
			node.getPool().extractGroup(p);
		}
		if (node.getPool().getCount() != before) {
			node.reconstructMiningPool();
		}
	}

	/**
	 * Drops the private branch. Its transactions that are not on the main chain go back to the
	 * pool: those of unpublished blocks, and those of published blocks that lost (reorg handling
	 * restored the latter too, but they were kept out while the branch was being mined).
	 */
	private void abandonBranch() {
		if (branch.isEmpty()) return;
		int unpublished = branch.size() - published;
		for (Block p : branch) {
			for (Transaction t : p.getTransactions()) {
				if (!t.isDoubleSpent() && !node.blockchain.transactionInStructure(t.getID()) && !node.getPool().contains(t)) {
					node.getPool().addTransaction(t);
				}
			}
		}
		Block last = branch.get(branch.size() - 1);
		clearBranch();
		if (unpublished > 0) {
			report(last, "Selfish: private branch abandoned (" + unpublished + " unpublished)");
		}
		node.reconstructMiningPool();
		node.considerMining(Simulation.currTime);
	}

	private void clearBranch() {
		branch.clear();
		published = 0;
	}

	/** @return The number of blocks mined but not yet published. */
	public int withheldCount() {
		return branch.size() - published;
	}

	private void report(Block b, String event) {
		BitcoinReporter.reportBlockEvent(Simulation.currentSimulationID, Simulation.currTime,
				System.currentTimeMillis() - Simulation.sysStartTime, node.getID(), b.getID(),
				(b.getParent() == null) ? -1 : b.getParent().getID(), b.getHeight(), b.printIDs(";"), event,
				b.getValidationDifficulty(), b.getValidationCycles());
	}
}
