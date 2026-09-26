package ca.yorku.cmg.cnsim.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** When do simulations stop sharing the mining random stream? */
class ReplicaIndependenceTest {

	private static final long END = 16_800_000;

	@Test
	void independentFromTheStartIsFine() {
		assertNull(NodeSamplerFactory.replicaIndependenceWarning(new long[] {444, 222}, new boolean[] {false, true}, new long[] {0}, END));
		assertNull(NodeSamplerFactory.replicaIndependenceWarning(new long[] {444}, new boolean[] {true}, null, END));
	}

	@Test
	void switchingAtTheEndMeansReplicasShareEverything() {
		// The thesis configs: switch time == sim.terminate.atTime.
		String w = NodeSamplerFactory.replicaIndependenceWarning(new long[] {444, 222}, new boolean[] {false, true}, new long[] {END}, END);
		assertNotNull(w);
		assertTrue(w.contains("never switches"));
		assertNotNull(NodeSamplerFactory.replicaIndependenceWarning(new long[] {444}, new boolean[] {false}, null, END));
	}

	@Test
	void switchingLaterThanZeroIsFlagged() {
		String w = NodeSamplerFactory.replicaIndependenceWarning(new long[] {444, 222}, new boolean[] {false, true}, new long[] {1000}, END);
		assertNotNull(w);
		assertTrue(w.contains("t = 1000"));
	}
}
