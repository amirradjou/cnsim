package ca.yorku.cmg.cnsim.engine.consensus;

import ca.yorku.cmg.cnsim.engine.node.Node;

/**
 * Decides when a node that is trying to produce a block next succeeds. Proof of work is the
 * engine's built-in rule (an exponential mining interval from hash power and difficulty); an
 * implementation of this interface replaces it, e.g. with a proof-of-stake slot lottery.
 *
 * @author Amirreza Radjou
 */
public interface LeaderElection {

	/**
	 * @param node The node that starts trying to produce a block.
	 * @param now The current simulation time (ms).
	 * @return Milliseconds from {@code now} until the node produces its next block, assuming it
	 *         keeps trying; at least 1.
	 */
	long nextBlockDelay(Node node, long now);

	/** @return A one-line description for the run log. */
	String describe();
}
