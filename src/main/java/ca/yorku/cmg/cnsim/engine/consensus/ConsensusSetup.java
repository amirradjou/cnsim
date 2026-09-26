package ca.yorku.cmg.cnsim.engine.consensus;

import ca.yorku.cmg.cnsim.engine.Config;
import ca.yorku.cmg.cnsim.engine.Simulation;
import ca.yorku.cmg.cnsim.engine.node.INode;
import ca.yorku.cmg.cnsim.engine.node.NodeSet;

/**
 * Configures block production once a simulation's nodes exist.
 * <ul>
 * <li>{@code consensus.leaderElection = pow} (default): proof of work. The difficulty is
 * {@code pow.difficulty}, or, when {@code pow.targetBlockInterval} (ms) is set, derived from the
 * total hash power of the created nodes so that the network finds a block every
 * {@code targetBlockInterval} on average.</li>
 * <li>{@code consensus.leaderElection = slot-lottery}: proof of stake with
 * {@link SlotLotteryElection}; keys {@code pos.slotDuration} (ms, default 1000) and
 * {@code pos.activeSlotCoefficient} (default 0.05). Node hash-power values are read as stake.</li>
 * </ul>
 *
 * @author Amirreza Radjou
 */
public final class ConsensusSetup {

	public static final String LEADER_ELECTION_KEY = "consensus.leaderElection";
	public static final String TARGET_INTERVAL_KEY = "pow.targetBlockInterval";

	private ConsensusSetup() {
	}

	/**
	 * Applies the configured block-production rule to {@code sim}.
	 * @return The proof-of-work difficulty in effect, or NaN under proof of stake.
	 */
	public static double configure(Simulation sim, NodeSet ns) {
		String kind = Config.getPropertyString(LEADER_ELECTION_KEY);
		kind = (kind == null || kind.isBlank()) ? "pow" : kind.trim().toLowerCase();
		double totalPower = totalHashPower(ns);

		switch (kind) {
			case "pow": {
				if (Config.hasProperty(TARGET_INTERVAL_KEY)) {
					double difficulty = difficultyForInterval(Config.getPropertyDouble(TARGET_INTERVAL_KEY), totalPower);
					sim.getSampler().getNodeSampler().setCurrentDifficulty(difficulty);
					return difficulty;
				}
				if (!Config.hasProperty("pow.difficulty")) {
					throw new IllegalArgumentException("Proof of work needs pow.difficulty or " + TARGET_INTERVAL_KEY);
				}
				return sim.getSampler().getNodeSampler().getCurrentDifficulty();
			}
			case "slot-lottery": {
				long slot = Config.hasProperty("pos.slotDuration") ? Config.getPropertyLong("pos.slotDuration") : 1000L;
				double f = Config.hasProperty("pos.activeSlotCoefficient")
						? Config.getPropertyDouble("pos.activeSlotCoefficient") : 0.05;
				sim.setLeaderElection(new SlotLotteryElection(slot, f, totalPower, sim.getSampler().getNodeSampler()));
				return Double.NaN;
			}
			default:
				throw new IllegalArgumentException("Unknown " + LEADER_ELECTION_KEY + " '" + kind + "'. Use pow or slot-lottery");
		}
	}

	/**
	 * The difficulty at which nodes with {@code totalHashPower} GH/s in total find a block every
	 * {@code intervalMs} on average. A node's attempts until success are geometric with success
	 * probability 1/difficulty, so the network's mean interval is difficulty / (total hashes per
	 * second).
	 * @param intervalMs Target mean block interval in milliseconds.
	 * @param totalHashPower Total hash power in GH/s.
	 * @return The difficulty ([search space] / [success space]).
	 */
	public static double difficultyForInterval(double intervalMs, double totalHashPower) {
		if (!(intervalMs > 0)) {
			throw new IllegalArgumentException(TARGET_INTERVAL_KEY + " must be positive, got " + intervalMs);
		}
		if (!(totalHashPower > 0)) {
			throw new IllegalArgumentException("Total hash power must be positive, got " + totalHashPower);
		}
		return intervalMs / 1000.0 * totalHashPower * 1e9;
	}

	private static double totalHashPower(NodeSet ns) {
		double total = 0;
		for (INode n : ns.getNodes()) {
			total += n.getHashPower();
		}
		return total;
	}
}
