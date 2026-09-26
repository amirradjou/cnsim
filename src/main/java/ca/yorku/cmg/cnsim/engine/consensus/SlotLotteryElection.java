package ca.yorku.cmg.cnsim.engine.consensus;

import ca.yorku.cmg.cnsim.engine.AbstractNodeSampler;
import ca.yorku.cmg.cnsim.engine.node.Node;

/**
 * Proof-of-stake leader election in the style of Ouroboros Praos (Cardano). Time is divided into
 * slots; in every slot each node independently wins the right to produce a block with probability
 * {@code phi(alpha) = 1 - (1 - f)^alpha}, where {@code alpha} is its share of the total stake and
 * {@code f} the active slot coefficient. The chance that a slot has at least one leader is then
 * exactly {@code f}, so blocks arrive on average every {@code slotDuration / f}; several leaders
 * in one slot produce competing blocks (slot battles) and forks.
 * <p>
 * Stake is read from each node's hash-power value (node list file or sampler), so only relative
 * values matter. Slots won are drawn independently per slot, which is what the verifiable random
 * function of the real protocol provides; redrawing when a node restarts is therefore valid.
 *
 * @author Amirreza Radjou
 */
public class SlotLotteryElection implements LeaderElection {

	private final long slotDuration;
	private final double activeSlotCoefficient;
	private final double totalStake;
	private final AbstractNodeSampler sampler;

	/**
	 * @param slotDuration Slot length in ms (Cardano: 1000).
	 * @param activeSlotCoefficient Probability {@code f} that a slot has a leader (Cardano: 0.05).
	 * @param totalStake Sum of the stake of all nodes.
	 * @param sampler The node sampler whose random stream draws the lottery outcomes.
	 */
	public SlotLotteryElection(long slotDuration, double activeSlotCoefficient, double totalStake,
			AbstractNodeSampler sampler) {
		if (slotDuration < 1) {
			throw new IllegalArgumentException("pos.slotDuration must be at least 1 ms, got " + slotDuration);
		}
		if (!(activeSlotCoefficient > 0 && activeSlotCoefficient <= 1)) {
			throw new IllegalArgumentException("pos.activeSlotCoefficient must be in (0, 1], got " + activeSlotCoefficient);
		}
		if (!(totalStake > 0)) {
			throw new IllegalArgumentException("Total stake must be positive, got " + totalStake);
		}
		this.slotDuration = slotDuration;
		this.activeSlotCoefficient = activeSlotCoefficient;
		this.totalStake = totalStake;
		this.sampler = sampler;
	}

	/**
	 * @param stake A node's stake.
	 * @return The probability that the node leads a given slot.
	 */
	public double leaderProbability(double stake) {
		double alpha = stake / totalStake;
		return 1 - Math.pow(1 - activeSlotCoefficient, alpha);
	}

	/**
	 * The node's next block is at the start of the first slot it wins among those beginning
	 * strictly after {@code now}.
	 */
	@Override
	public long nextBlockDelay(Node node, long now) {
		long slots = sampler.getNextLeaderSlots(leaderProbability(node.getHashPower()));
		if (slots >= Long.MAX_VALUE / (4 * slotDuration)) {
			return Long.MAX_VALUE / 4; // no stake: never leads
		}
		long firstSlot = now / slotDuration + 1;
		long blockTime = (firstSlot + slots - 1) * slotDuration;
		return blockTime - now;
	}

	public long getSlotDuration() {
		return slotDuration;
	}

	public double getActiveSlotCoefficient() {
		return activeSlotCoefficient;
	}

	@Override
	public String describe() {
		return String.format("slot lottery (Ouroboros Praos style): %d ms slots, f = %s, mean block interval %.0f ms",
				slotDuration, activeSlotCoefficient, slotDuration / activeSlotCoefficient);
	}
}
