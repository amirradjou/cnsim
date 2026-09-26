package ca.yorku.cmg.cnsim.bitcoin;
import ca.yorku.cmg.cnsim.engine.Config;
import ca.yorku.cmg.cnsim.engine.Debug;
import ca.yorku.cmg.cnsim.engine.Simulation;
import ca.yorku.cmg.cnsim.engine.transaction.ITxContainer;
import ca.yorku.cmg.cnsim.engine.transaction.Transaction;

import java.util.ArrayList;
import java.util.List;

public class MaliciousNodeBehavior implements NodeBehaviorStrategy {
    /** Config keys for the reveal thresholds; the defaults are the values the thesis used. */
    public static final String MIN_CHAIN_LENGTH_KEY = "bitcoin.attack.minChainLength";
    public static final String MAX_CHAIN_LENGTH_KEY = "bitcoin.attack.maxChainLength";
    public static final int DEFAULT_MIN_CHAIN_LENGTH = 2;
    public static final int DEFAULT_MAX_CHAIN_LENGTH = 15;

    /** The public chain must grow by more than this many blocks before the hidden chain is revealed. */
    private final int minChainLength;
    /** Past this public growth the attacker reveals (if ahead) or gives up. */
    private final int maxChainLength;

    private ArrayList<Block> hiddenChain=new ArrayList<Block>();
    private Transaction targetTransaction;
	private int targetTxID;
	
    private boolean isAttackInProgress = false;
    private boolean isAttackCompleted = false; // New flag to track if attack has been completed
    private BitcoinNode node;
    private HonestNodeBehavior honestBehavior;
    private int blockchainSizeAtAttackStart;
    private Block lastBlock;
    private int publicChainGrowthSinceAttack;


    
    /**
     * Constructor. Creates also a shadow honest behavior object.
     * @param node The node which has the behavior.
     */
    public MaliciousNodeBehavior(BitcoinNode node) {
        this.isAttackInProgress = false;
        this.node = node;
        this.honestBehavior = new HonestNodeBehavior(node);
        this.minChainLength = Config.hasProperty(MIN_CHAIN_LENGTH_KEY)
                ? Config.getPropertyInt(MIN_CHAIN_LENGTH_KEY) : DEFAULT_MIN_CHAIN_LENGTH;
        this.maxChainLength = Config.hasProperty(MAX_CHAIN_LENGTH_KEY)
                ? Config.getPropertyInt(MAX_CHAIN_LENGTH_KEY) : DEFAULT_MAX_CHAIN_LENGTH;
        if (minChainLength < 0 || maxChainLength < minChainLength) {
            throw new IllegalArgumentException("Need 0 <= " + MIN_CHAIN_LENGTH_KEY + " <= " + MAX_CHAIN_LENGTH_KEY
                    + ", got " + minChainLength + " and " + maxChainLength);
        }
    }



    @Override
    public void event_NodeReceivesClientTransaction(Transaction t, long time) {
        // If attack is completed and this is the target transaction, don't add it to the pool
        if (isAttackCompleted && t.getID() == targetTxID) {
            Debug.p("Target transaction " + targetTxID + " received after attack completion - ignoring");
            return;
        }
        if (!isAttackInProgress) {
            honestBehavior.event_NodeReceivesClientTransaction(t, time);
        } else {
            // During attack, always filter target transaction
            if (t.getID() != targetTxID) {
                honestBehavior.event_NodeReceivesClientTransaction(t, time);
            }
            filterTargetTransactionFromPools();
        }
        persistentFilterTargetTransaction();
    }

    @Override
    public void event_NodeReceivesPropagatedTransaction(Transaction t, long time) {
        // If attack is completed and this is the target transaction, don't add it to the pool
        if (isAttackCompleted && t.getID() == targetTxID) {
            Debug.p("Target transaction " + targetTxID + " received via propagation after attack completion - ignoring");
            return;
        }
        if (!isAttackInProgress) {
            honestBehavior.event_NodeReceivesPropagatedTransaction(t, time);
        } else {
            // During attack, always filter target transaction
            if (t.getID() != targetTxID) {
                honestBehavior.event_NodeReceivesPropagatedTransaction(t, time);
            }
            filterTargetTransactionFromPools();
        }
        persistentFilterTargetTransaction();
    }

    private void startAttack(Block b) {
        // Keep the target so it can be marked double-spent when the hidden chain is revealed.
        Transaction target = b.getTransactionById(targetTxID);
        if (target != null) {
            targetTransaction = target;
        }
        BitcoinReporter.reportBlockEvent(
				Simulation.currentSimulationID,
        		Simulation.currTime,
        		System.currentTimeMillis() - Simulation.sysStartTime,
        		b.getCurrentNodeID(),
                b.getID(),
                ((b.getParent() == null) ? -1 : b.getParent().getID()),
                b.getHeight(),
                b.printIDs(";"),
                "Target Transaction Appeared - Attack Starts", 
                b.getValidationDifficulty(),
                b.getValidationCycles());
        isAttackInProgress = true;
        calculateBlockchainSizeAtAttackStart();
        Debug.p("Starting attack! at time " + Simulation.currTime);
    }


    @Override
    public void event_NodeReceivesPropagatedContainer(ITxContainer t) {
        Block b = (Block) t;
        
        //updateBlockContext(b);
        
        b.setCurrentNodeID(node.getID());
        b.setLastBlockEvent("Node Receives Propagated Block");
        b.setValidationCycles(-1.0);
        b.setValidationDifficulty(-1.0);
     
        BitcoinReporter.reportBlockEvent(
				Simulation.currentSimulationID,
        		Simulation.currTime,
        		System.currentTimeMillis() - Simulation.sysStartTime,
        		b.getCurrentNodeID(),
                b.getID(),
                ((b.getParent() == null) ? -1 : b.getParent().getID()),b.getHeight(),
                b.printIDs(";"),
                b.getLastBlockEvent(), 
                b.getValidationDifficulty(),
                b.getValidationCycles());
        
        //TODO: why is this below a t and not a b?
        if (!isAttackInProgress && t.contains(targetTxID)) {
            lastBlock = (Block) b.parent;
            if (!node.blockchain.contains(b)) {
                //reportBlockEvent(b, b.getContext().blockEvt);
                handleNewBlockReceptionInAttack(b);
                startAttack(b);
                filterTargetTransactionFromPools();
            } else { //Does not contain target transaction
                BitcoinReporter.reportBlockEvent(
						Simulation.currentSimulationID,
                		Simulation.currTime,
                		System.currentTimeMillis() - Simulation.sysStartTime,
                		b.getCurrentNodeID(),
                        b.getID(),
                        ((b.getParent() == null) ? -1 : b.getParent().getID()),b.getHeight(),
                        b.printIDs(";"),
                        "Propagated Block Discarded (already exists)", 
                        b.getValidationDifficulty(),
                        b.getValidationCycles());
                //reportBlockEvent(b, "Propagated Block Discarded");
            }
        }
        else if (isAttackInProgress) { //attack is in progress or block does not contain target
            if (!node.blockchain.contains(b)) {
                //reportBlockEvent(b, b.getContext().blockEvt);
                handleNewBlockReceptionInAttack(b);
            } else {
                //Discard the block and report the event.
                BitcoinReporter.reportBlockEvent(
						Simulation.currentSimulationID,
                		Simulation.currTime,
                		System.currentTimeMillis() - Simulation.sysStartTime,
                		b.getCurrentNodeID(),
                        b.getID(),
                        ((b.getParent() == null) ? -1 : b.getParent().getID()),b.getHeight(),
                        b.printIDs(";"),
                        "Propagated Block Discarded (already exists)", 
                        b.getValidationDifficulty(),
                        b.getValidationCycles());
                //reportBlockEvent(b, "Propagated Block Discarded");
            }
            checkAndRevealHiddenChain(b);
            abandonAttackIfBeaten(b);
        }
        else { //attack not in progress
            if (!node.blockchain.contains(b)) {
                //reportBlockEvent(b, b.getContext().blockEvt);
                honestBehavior.handleNewBlockReception(b);
                
                // If attack is completed, ensure target transaction is removed from pools after block processing
                if (isAttackCompleted && b.contains(targetTxID)) {
                    node.removeFromPool(targetTxID);
                    node.miningPool.removeTransaction(targetTxID);
                }
                ensureTargetTransactionExcluded();
            } else {
            	//reportBlockEvent(b, "Propagated Block Discarded");
                BitcoinReporter.reportBlockEvent(
						Simulation.currentSimulationID,
                		Simulation.currTime,
                		System.currentTimeMillis() - Simulation.sysStartTime,
                		b.getCurrentNodeID(),
                        b.getID(),
                        ((b.getParent() == null) ? -1 : b.getParent().getID()),b.getHeight(),
                        b.printIDs(";"),
                        "Propagated Block Discarded (already exists)", 
                        b.getValidationDifficulty(),
                        b.getValidationCycles());
            }

        }
    }



    @Override
    public void event_NodeCompletesValidation(ITxContainer t, long time) {
        if (isAttackInProgress) {
            // During attack, always filter target transaction before mining
            filterTargetTransactionFromPools();
            Block newBlock = (Block) t;
            // Do not add the target transaction to the block
            if (newBlock.contains(targetTxID)) {
                newBlock.removeTransaction(targetTxID);
            }
            newBlock.validateBlock(node.miningPool,
            		Simulation.currTime, 
            		System.currentTimeMillis()- Simulation.sysStartTime, 
            		node.getID(), 
            		"Node Completes Validation", 
            		node.getOperatingDifficulty(), 
            		node.getProspectiveCycles());
            
            node.completeValidation(node.miningPool, time);

            BitcoinReporter.reportBlockEvent(
					Simulation.currentSimulationID,
            		newBlock.getSimTime_validation(),
            		newBlock.getSysTime_validation() - Simulation.sysStartTime,
            		newBlock.getValidationNodeID(),
            		newBlock.getID(),((newBlock.getParent() == null) ? -1 : newBlock.getParent().getID()),
            		newBlock.getHeight(),
            		newBlock.printIDs(";"),
                    "Node Completes Validation",
                    newBlock.getValidationDifficulty(),
                    newBlock.getValidationCycles());
            
            
            if (!node.blockchain.contains(newBlock)) {
                //reportBlockEvent(newBlock, newBlock.getContext().blockEvt);
                BitcoinReporter.reportBlockEvent(
						Simulation.currentSimulationID,
                		newBlock.getSimTime_validation(),
                		newBlock.getSysTime_validation() - Simulation.sysStartTime,
                		newBlock.getValidationNodeID(),
                		newBlock.getID(),((newBlock.getParent() == null) ? -1 : newBlock.getParent().getID()),
                		newBlock.getHeight(),
                		newBlock.printIDs(";"),
                        "Adding block to hidden chain",
                        newBlock.getValidationDifficulty(),
                        newBlock.getValidationCycles());
                hiddenChain.add(newBlock);
            } else {
                //System.out.println(node.getID()+ " contains " + newBlock.getID() + " in its blockchain in completes validation");
                //System.out.println(node.getID()+ " contains " + newBlock.getID() + " in its blockchain in completes validation");
                //reportBlockEvent(newBlock, "Discarding own Block (ERROR)");
                BitcoinReporter.reportBlockEvent(
						Simulation.currentSimulationID,
                		newBlock.getSimTime_validation(),
                		newBlock.getSysTime_validation() - Simulation.sysStartTime,
                		newBlock.getValidationNodeID(),
                		newBlock.getID(),((newBlock.getParent() == null) ? -1 : newBlock.getParent().getID()),
                		newBlock.getHeight(),
                		newBlock.printIDs(";"),
                        "ERROR: Discarding own Block",
                        newBlock.getValidationDifficulty(),
                        newBlock.getValidationCycles());
            }
            manageMiningPostValidation();
            checkAndRevealHiddenChain(newBlock);
            abandonAttackIfBeaten(newBlock);
        } else { //Attack not in progress
            Block b = (Block) t;
            b.validateBlock(node.miningPool,
            		Simulation.currTime, 
            		System.currentTimeMillis() - Simulation.sysStartTime, 
            		node.getID(), 
            		"Node Completes Validation", 
            		node.getOperatingDifficulty(), 
            		node.getProspectiveCycles());
            //node.completeValidation(node.miningPool, time);
            node.completeValidation(node.miningPool, time);


            
            if(b.contains(targetTxID)){
                if (!node.blockchain.contains(b)) {
                    //Report validation
                    //reportBlockEvent(b, b.getContext().blockEvt);
                    BitcoinReporter.reportBlockEvent(
    						Simulation.currentSimulationID,
                    		b.getSimTime_validation(),
                    		b.getSysTime_validation() - Simulation.sysStartTime,
                    		b.getValidationNodeID(),
                    		b.getID(),((b.getParent() == null) ? -1 : b.getParent().getID()),
                    		b.getHeight(),
                    		b.printIDs(";"),
                            "Node Completes Validation",
                            b.getValidationDifficulty(),
                            b.getValidationCycles());
                    
                    startAttack(b);
                    node.blockchain.addToStructure(b);
                    node.propagateContainer(b, time);
                    lastBlock = (Block) b.parent;
                    // Mining restarts once, below. Restarting here as well used to leave the
                    // first new validation event scheduled but forgotten (stopMining and
                    // resetNextValidationEvent do not cancel it), so the attacker kept two
                    // mining events alive and mined at twice its hash rate.
                    filterTargetTransactionFromPools();
                } else {
                    BitcoinReporter.reportBlockEvent(
    						Simulation.currentSimulationID,
                    		b.getSimTime_validation(),
                    		b.getSysTime_validation() - Simulation.sysStartTime,
                    		b.getValidationNodeID(),
                    		b.getID(),((b.getParent() == null) ? -1 : b.getParent().getID()),
                    		b.getHeight(),
                    		b.printIDs(";"),
                            "Error: Discarding own Block",
                            b.getValidationDifficulty(),
                            b.getValidationCycles());
                    System.out.println(node.getID()+ " contains " + b.getID() + " in its blockchain in completes validation");
                    //reportBlockEvent(b, "Discarding own Block (ERROR)");
                }
                node.stopMining();
                node.resetNextValidationEvent();
                node.reconstructMiningPool();
                node.miningPool.removeTransaction(targetTxID);
                node.considerMining(Simulation.currTime);
            } else {
                b.setParent(node.blockchain.getLongestTip());
                if (!node.blockchain.contains(b)){
                    //reportBlockEvent(b, b.getContext().blockEvt);
                    BitcoinReporter.reportBlockEvent(
    						Simulation.currentSimulationID,
                    		b.getSimTime_validation(),
                    		b.getSysTime_validation() - Simulation.sysStartTime,
                    		b.getValidationNodeID(),
                    		b.getID(),((b.getParent() == null) ? -1 : b.getParent().getID()),
                    		b.getHeight(),
                    		b.printIDs(";"),
                            "Node Completes Validation",
                            b.getValidationDifficulty(),
                            b.getValidationCycles());
                	
                    b.setParent(null);
                    node.blockchain.addToStructure(b);
                    try {
                    	//Propagate a clone of the block to the rest of the network
						node.propagateContainer((ITxContainer) b.clone(), time);
					} catch (CloneNotSupportedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
                } else {
                    //reportBlockEvent(b, "Discarding own Block (ERROR)");
                    BitcoinReporter.reportBlockEvent(
    						Simulation.currentSimulationID,
                    		b.getSimTime_validation(),
                    		b.getSysTime_validation() - Simulation.sysStartTime,
                    		b.getValidationNodeID(),
                    		b.getID(),((b.getParent() == null) ? -1 : b.getParent().getID()),
                    		b.getHeight(),
                    		b.printIDs(";"),
                            "Error: Discarding own Block",
                            b.getValidationDifficulty(),
                            b.getValidationCycles());
                }
                honestBehavior.processPostValidationActivities(time);
            }
        }
    }

    private void revealHiddenChain() {
        for (int i = hiddenChain.size()-1; i >= 0; i--) {
            Block b = hiddenChain.get(i);
            b.parent = i==0 ? lastBlock : hiddenChain.get(i-1);
            node.blockchain.addReceivedBlock(b);
            node.propagateContainer(b, Simulation.currTime);
        }
        isAttackInProgress = false;
        isAttackCompleted = true; // Mark attack as completed
        // The published chain replaces the target: honest nodes must not mine it again when
        // they return abandoned transactions to their pools (bitcoin.reorg.restoreTransactions).
        if (targetTransaction != null) {
            targetTransaction.markDoubleSpent();
        }
        hiddenChain = new ArrayList<Block>();
        reconstructMiningPoolFiltered();
        
        Debug.p("Chain reveal! at time " + Simulation.currTime);
    }

    
    /*
    private void reportBlockEvent(Block b, String blockEvt) {
        BitcoinReporter.reportBlockEvent(
				Simulation.currentSimulationID,b.getContext().simTime, b.getContext().sysTime  - Simulation.sysStartTime, b.getContext().nodeID,
                b.getID(),((b.getParent() == null) ? -1 : b.getParent().getID()),b.getHeight(),b.printIDs(";"),
                blockEvt, b.getContext().difficulty,b.getContext().cycles);
    }

    private void updateBlockContext(Block b) {
        //TODO: updating of context here seems wrong!
        //Update context information for reporting
        b.getContext().simTime = Simulation.currTime;
        b.getContext().sysTime = System.currentTimeMillis() - Simulation.sysStartTime;
        b.getContext().nodeID = node.getID();
        b.getContext().blockEvt = "Node Receives Propagated Block";
        b.getContext().cycles = -1;
        b.getContext().difficulty = -1;
    }

     */

    public void setTargetTransaction(Transaction targetTransaction) {
        this.targetTransaction = targetTransaction;
    }

    public void setTargetTransaction(int targetTxID) {
        this.targetTxID = targetTxID;
    }

    
    
    private void ensureTargetTransactionExcluded() {
        if (isAttackCompleted) {
            node.removeFromPool(targetTxID);
            node.miningPool.removeTransaction(targetTxID);
        }
    }

    private void manageMiningPostValidation() {
        node.stopMining();
        node.resetNextValidationEvent();
        node.removeFromPool(node.miningPool);
        reconstructMiningPoolFiltered();
        node.considerMining(Simulation.currTime);
    }

    private void calculateBlockchainSizeAtAttackStart() {
        if (node.blockchain.getBlockchainHeight() == 0) {
            blockchainSizeAtAttackStart = 0;
            return;
        }
        Block tip = node.blockchain.getLongestTip();
        blockchainSizeAtAttackStart = tip.contains(targetTxID) ? tip.getHeight() - 1 : tip.getHeight();
    }

    private void handleNewBlockReceptionInAttack(Block b) {
        node.blockchain.addReceivedBlock(b);
        reconstructMiningPoolFiltered();
        node.considerMining(Simulation.currTime);
    }

    private boolean shouldRevealHiddenChain() {
        return shouldReveal(hiddenChain.size(), publicChainGrowthSinceAttack, minChainLength, maxChainLength);
    }

    /**
     * The reveal rule: reveal once the hidden chain is longer than the public chain's growth since
     * the attack started and that growth exceeds {@code min} (enough confirmations for the victim to
     * accept the payment), or once the growth exceeds {@code max} regardless.
     */
    static boolean shouldReveal(int hiddenLength, int publicGrowth, int min, int max) {
        return (hiddenLength > publicGrowth && publicGrowth > min) || publicGrowth > max;
    }

    /**
     * The give-up rule: the public chain has grown by {@code max} or more and the hidden chain is
     * not longer.
     */
    static boolean shouldAbandon(int hiddenLength, int publicGrowth, int max) {
        return publicGrowth >= max && hiddenLength <= publicGrowth;
    }

    private void checkAndRevealHiddenChain(Block b) {
        publicChainGrowthSinceAttack = node.blockchain.getLongestTip().height - blockchainSizeAtAttackStart;
        if (shouldRevealHiddenChain()) {
            BitcoinReporter.reportBlockEvent(
					Simulation.currentSimulationID,
            		Simulation.currTime,
            		System.currentTimeMillis() - Simulation.sysStartTime,
            		b.getCurrentNodeID(),
                    b.getID(),
                    ((b.getParent() == null) ? -1 : b.getParent().getID()),b.getHeight(),
                    b.printIDs(";"),
                    "Reveal of hidden chain starts here.", 
                    b.getValidationDifficulty(),
                    b.getValidationCycles());
            revealHiddenChain();
        }
    }

    /**
     * Ensures the target transaction is removed from both the mining pool and the transaction pool if the attack is completed.
     */
    private void persistentFilterTargetTransaction() {
        if (isAttackCompleted) {
            node.getPool().removeTransaction(targetTxID);
            node.miningPool.removeTransaction(targetTxID);
        }
    }

    /**
     * Reconstructs the mining pool, ensuring the target transaction is filtered from both the pool and mining pool if the attack is completed.
     */
    private void reconstructMiningPoolFiltered() {
        persistentFilterTargetTransaction(); // Remove from pool before reconstruction
        node.reconstructMiningPool();
        persistentFilterTargetTransaction(); // Remove from mining pool after reconstruction
    }

    // Helper: Check if the target transaction is in the public chain
    private boolean isTargetTxInPublicChain() {
        return node.blockchain.transactionInStructure(targetTxID);
    }

    // Helper: Remove the target transaction from both pools
    private void filterTargetTransactionFromPools() {
        node.getPool().removeTransaction(targetTxID);
        node.miningPool.removeTransaction(targetTxID);
    }

    // Helper: Should the attack be abandoned?
    /**
     * Gives up when the public chain has grown past the maximum and the hidden chain is not
     * longer, and records that in the block log.
     */
    private void abandonAttackIfBeaten(Block b) {
        if (!shouldAbandonAttack()) return;
        BitcoinReporter.reportBlockEvent(
                Simulation.currentSimulationID,
                Simulation.currTime,
                System.currentTimeMillis() - Simulation.sysStartTime,
                node.getID(),
                b.getID(),
                ((b.getParent() == null) ? -1 : b.getParent().getID()), b.getHeight(),
                b.printIDs(";"),
                "Attack abandoned (hidden " + hiddenChain.size() + ", public growth " + publicChainGrowthSinceAttack + ")",
                b.getValidationDifficulty(),
                b.getValidationCycles());
        isAttackInProgress = false;
        List<Block> discarded = new ArrayList<>(hiddenChain);
        hiddenChain.clear();
        resyncPoolWithMainChain(discarded);
        Debug.p("Attack abandoned: public chain growth threshold reached and hidden chain not longer.");
    }

    /**
     * After giving up, the attacker follows the public chain again. Its pool may still hold
     * transactions that chain already contains (when it mined the target's block itself it kept
     * that block's transactions for the hidden chain), and every block it mined from them would
     * be discarded as overlapping its own chain. Drop those, and give back the transactions that
     * only the discarded hidden blocks held.
     */
    private void resyncPoolWithMainChain(List<Block> discardedHiddenBlocks) {
        List<Transaction> onMainChain = new ArrayList<>();
        for (Transaction t : node.getPool().getTransactions()) {
            if (node.blockchain.transactionInStructure(t.getID())) {
                onMainChain.add(t);
            }
        }
        node.getPool().removeAllOf(onMainChain);
        for (Block h : discardedHiddenBlocks) {
            for (Transaction t : h.getTransactions()) {
                if (!node.blockchain.transactionInStructure(t.getID()) && !node.getPool().contains(t)) {
                    node.getPool().addTransaction(t);
                }
            }
        }
        reconstructMiningPoolFiltered();
        node.considerMining(Simulation.currTime);
    }

    private boolean shouldAbandonAttack() {
        return shouldAbandon(hiddenChain.size(), publicChainGrowthSinceAttack, maxChainLength);
    }
}



