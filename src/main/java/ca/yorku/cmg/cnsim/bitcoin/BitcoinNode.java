package ca.yorku.cmg.cnsim.bitcoin;

import ca.yorku.cmg.cnsim.engine.Config;
import ca.yorku.cmg.cnsim.engine.IStructure;
import ca.yorku.cmg.cnsim.engine.Simulation;
import ca.yorku.cmg.cnsim.engine.node.INode;
import ca.yorku.cmg.cnsim.engine.node.Node;
import ca.yorku.cmg.cnsim.engine.reporter.Reporter;
import ca.yorku.cmg.cnsim.engine.transaction.ITxContainer;
import ca.yorku.cmg.cnsim.engine.transaction.Transaction;
import ca.yorku.cmg.cnsim.engine.transaction.TransactionGroup;
import ca.yorku.cmg.cnsim.engine.transaction.TxValuePerSizeComparator;

/**
 * @author Enterprise Systems Group (ESG) @ York University
 *
 */
public class BitcoinNode extends Node {
	/**
	 * Fee-per-byte ordering for block assembly. One shared instance, so the pool's sorted view
	 * survives between calls to {@link TransactionGroup#getTopN(float, java.util.Comparator)}.
	 */
	private static final TxValuePerSizeComparator FEE_RATE_ORDER = new TxValuePerSizeComparator();

	private NodeBehaviorStrategy behaviorStrategy;

	protected TransactionGroup miningPool;
	public Blockchain blockchain;

	protected Double operatingDifficulty;
	protected long minValueToMine;
	protected long minSizeToMine;

	/** Config key: return transactions of blocks that leave the main chain to the pool. */
	public static final String RESTORE_ON_REORG_KEY = "bitcoin.reorg.restoreTransactions";
	/**
	 * When the main chain switches branches, whether transactions that were only in the
	 * abandoned blocks go back into the pool (as in Bitcoin Core). Off by default, which keeps
	 * the original behaviour where they are lost; that matters only when forks are frequent.
	 */
	protected final boolean restoreOnReorg;

	public void _______________Constructors() {}

	/** Config key: {@code first-seen} makes ties between equal branches go to the first-received tip. */
	public static final String TIE_BREAK_KEY = "consensus.tieBreak";

	private static Blockchain newBlockchain() {
		String rule = Config.getPropertyString(TIE_BREAK_KEY);
		if (rule == null || rule.isBlank() || rule.trim().equalsIgnoreCase("legacy")) {
			return new Blockchain(false);
		}
		if (rule.trim().equalsIgnoreCase("first-seen")) {
			return new Blockchain(true);
		}
		throw new IllegalArgumentException("Unknown " + TIE_BREAK_KEY + " '" + rule + "'. Use legacy or first-seen");
	}

	public BitcoinNode(Simulation sim) {
		super(sim);
		blockchain = newBlockchain();
		miningPool = new TransactionGroup();
		minValueToMine = Config.getPropertyLong("bitcoin.minValueToMine");
		minSizeToMine = Config.getPropertyLong("bitcoin.minSizeToMine");
		this.operatingDifficulty = configuredDifficulty();
		this.restoreOnReorg = Config.hasProperty(RESTORE_ON_REORG_KEY) && Config.getPropertyBoolean(RESTORE_ON_REORG_KEY);
	}
	public BitcoinNode(Simulation sim, NodeBehaviorStrategy behaviorStrategy) {
		super(sim);
		this.behaviorStrategy = behaviorStrategy;
		blockchain = newBlockchain();
		miningPool = new TransactionGroup();
		minValueToMine = Config.getPropertyLong("bitcoin.minValueToMine");
		minSizeToMine = Config.getPropertyLong("bitcoin.minSizeToMine");

		this.operatingDifficulty = configuredDifficulty();
		this.restoreOnReorg = Config.hasProperty(RESTORE_ON_REORG_KEY) && Config.getPropertyBoolean(RESTORE_ON_REORG_KEY);
	}

	/**
	 * pow.difficulty, or -1 when it is not set (it may be derived from pow.targetBlockInterval
	 * once all nodes exist, or not apply at all under proof of stake).
	 */
	private static double configuredDifficulty() {
		return Config.hasProperty("pow.difficulty") ? Config.getPropertyDouble("pow.difficulty") : -1;
	}



	public TransactionGroup getMiningPool() {
		return miningPool;
	}


	public void setMiningPool(TransactionGroup miningPool) {
		this.miningPool = miningPool;
	}


	public void setStructure(Blockchain blockchain) {
		this.blockchain = blockchain;
	}


	public void setOperatingDifficulty(Double operatingDifficulty) {
		this.operatingDifficulty = operatingDifficulty;
	}


	@Override
	public IStructure getStructure() {
		return blockchain;
	}


	public void _______________PoW_and_Mining() {}

	public void setOperatingDifficulty (double dif) {
		this.operatingDifficulty = dif;
	}

	public double getOperatingDifficulty () {
		return (this.operatingDifficulty);
	}


	public long getMinValueToMine() {
		return minValueToMine;
	}


	public void setMinValueToMine(long minValueToMine) {
		this.minValueToMine = minValueToMine;
	}


	public long getMinSizeToMine() {
		return minSizeToMine;
	}

	public void setMinSizeToMine(long minSizeToMine) {
		this.minSizeToMine = minSizeToMine;
	}


	protected void considerMining(long time) {
		if (isWorthMining()) {
			//Start mining and schedule a new validation event
			if (!isMining()) {
				//It is not mining because it has never OR it has but then abandoned.
				assert((getNextValidationEvent() == null) || ((getNextValidationEvent() != null) ? getNextValidationEvent().ignoreEvt(): true));

				long interval = scheduleValidationEvent(new Block(miningPool.getTransactions()), time);
				startMining(interval);
			} else {
				assert((getNextValidationEvent() != null) && !getNextValidationEvent().ignoreEvt());
				//All good!
			}
		} else {
			if (!isMining()) {
				assert((getNextValidationEvent() == null) || getNextValidationEvent().ignoreEvt());
				//All good otherwise!
			} else  {
				// Stop mining, invalidate any future validation event.
				assert((getNextValidationEvent() != null) && !getNextValidationEvent().ignoreEvt());
				getNextValidationEvent().ignoreEvt(true);
				stopMining();
				assert((getNextValidationEvent() == null) || ((getNextValidationEvent() != null) ? getNextValidationEvent().ignoreEvt(): true));
			}
		}

	}


	public boolean isWorthMining() {
		return((miningPool.getValue() > getMinValueToMine()));
	}

	/**
	 * @return The current main-chain tip if reorg handling is on, else {@code null}; pass it to
	 *         {@link #reconcilePoolAfterReorg(Block)} after changing the structure.
	 */
	protected Block tipBeforeChange() {
		return restoreOnReorg ? blockchain.getLongestTip() : null;
	}

	/**
	 * If the main chain moved to another branch since {@code oldTip}, removes the transactions of
	 * newly adopted blocks from the pool and returns to it those of abandoned blocks that are not
	 * on the new main chain (unless double-spent). No-op when reorg handling is off.
	 * @param oldTip The tip returned by {@link #tipBeforeChange()}.
	 */
	protected void reconcilePoolAfterReorg(Block oldTip) {
		if (!restoreOnReorg || oldTip == null) return;
		Block newTip = blockchain.getLongestTip();
		if (newTip == null || newTip.getID() == oldTip.getID()) return;
		Block parent = (Block) newTip.getParent();
		if (parent != null && parent.getID() == oldTip.getID()) return; // plain extension
		Blockchain.Reorg r = Blockchain.reorg(oldTip, newTip);
		for (Block adopted : r.adopted()) {
			pool.extractGroup(adopted);
		}
		int restored = 0;
		for (Block abandoned : r.abandoned()) {
			for (Transaction t : abandoned.getTransactions()) {
				if (!t.isDoubleSpent() && !blockchain.transactionInStructure(t.getID()) && !pool.contains(t)) {
					pool.addTransaction(t);
					restored++;
				}
			}
		}
		if (!r.abandoned().isEmpty()) {
			BitcoinReporter.reportBlockEvent(sim.getSimID(), Simulation.currTime,
					System.currentTimeMillis() - Simulation.sysStartTime, getID(), newTip.getID(),
					parent == null ? -1 : parent.getID(), newTip.getHeight(), "{}",
					"Reorg: " + r.abandoned().size() + " block(s) abandoned, " + restored + " tx restored",
					-1, -1);
		}
	}

	protected void reconstructMiningPool() {
		miningPool  = pool.getTopN(Config.getPropertyLong("bitcoin.maxBlockSize"), FEE_RATE_ORDER);
		//miningPool.extractGroup(blockchain.getAllOrphanTransactions());
	}

	
	protected void transactionReceipt(Transaction t, long time) {
		addTransactionToPool(t);
		reconstructMiningPool();
		considerMining(time);
	}


	@Override
	public void close(INode n) {
		BitcoinReporter.reportBlockChainState(
				//Simulation.currTime, System.currentTimeMillis(), this.getID(),
				this.blockchain.printStructureReport(this.getID()), 
				this.blockchain.printOrphansReport(this.getID()));
	}


	@Override
	public void event_NodeReceivesClientTransaction(Transaction t, long time) {
		behaviorStrategy.event_NodeReceivesClientTransaction(t, time);
	}


	@Override
	public void event_NodeReceivesPropagatedContainer(ITxContainer t) {
		behaviorStrategy.event_NodeReceivesPropagatedContainer(t);
	}


	public void event_NodeReceivesPropagatedTransaction(Transaction t, long time) {
		behaviorStrategy.event_NodeReceivesPropagatedTransaction(t, time);
	}

	@Override
	public void event_NodeCompletesValidation(ITxContainer t, long time) {
		behaviorStrategy.event_NodeCompletesValidation(t, time);
	}


	public double getProspectiveCycles() {
		return super.prospectiveMiningCycles;
	}

	public void completeValidation(TransactionGroup miningPool, long time) {
		super.event_NodeCompletesValidation(miningPool, time);
		// Any additional logic that needs to be executed after calling the super method
	}

	public void setBehaviorStrategy(NodeBehaviorStrategy strategy) {
		this.behaviorStrategy = strategy;
	}


	public NodeBehaviorStrategy getBehaviorStrategy() {
		return behaviorStrategy;
	}
	
	public Blockchain getBlockchain() {
		return blockchain;
	}

	

	
	//
	// REPORTING ROUTINES
	//
	
	
	
	@Override
	public void timeAdvancementReport() {
		// TODO Auto-generated method stub
	}

	@Override
	public void periodicReport() {
		// TODO Auto-generated method stub
	}

	
	
	@Override
	public void beliefReport(long[] sample, long time) {
		for (int i = 0; i < sample.length; i++) {
			Reporter.addBeliefEntry(this.sim.getSimID(), this.getID(), sample[i], blockchain.transactionInStructure(sample[i]), time);
		}
	}

	@Override
	public void nodeStatusReport() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void structureReport() {
		// TODO Auto-generated method stub
		
	}

}
