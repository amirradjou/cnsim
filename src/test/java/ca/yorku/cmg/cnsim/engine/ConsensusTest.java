package ca.yorku.cmg.cnsim.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.yorku.cmg.cnsim.engine.consensus.ConsensusSetup;
import ca.yorku.cmg.cnsim.engine.consensus.SlotLotteryElection;
import ca.yorku.cmg.cnsim.engine.node.NodeStub;

/**
 * Proof-of-work difficulty from a target block interval, and the proof-of-stake slot lottery.
 * The statistical checks use fixed seeds and generous tolerances (well over 5 standard errors).
 */
class ConsensusTest {

	private Properties savedProps;
	private boolean savedInitialized;

	@BeforeEach
	void setUp() {
		savedProps = new Properties();
		savedProps.putAll(Config.prop);
		savedInitialized = Config.initialized;
		Config.init("src/test/resources/application.properties");
	}

	@AfterEach
	void restore() {
		Config.prop.clear();
		Config.prop.putAll(savedProps);
		Config.initialized = savedInitialized;
	}

	private static StandardNodeSampler seededSampler(long seed) {
		return new StandardNodeSampler(new Sampler(), new long[] {seed}, new boolean[] {false}, 1);
	}

	@Test
	void difficultyForIntervalScalesWithIntervalAndHashPower() {
		assertEquals(6e20, ConsensusSetup.difficultyForInterval(600_000, 1e9), 1e8);
		assertThrows(IllegalArgumentException.class, () -> ConsensusSetup.difficultyForInterval(0, 1e9));
		assertThrows(IllegalArgumentException.class, () -> ConsensusSetup.difficultyForInterval(1000, 0));
	}

	@Test
	void networkBlockIntervalMatchesTheTarget() {
		double[] hashPower = {70e9, 40e9, 25e9, 10e9, 5e9}; // GH/s
		double total = 0;
		for (double h : hashPower) total += h;
		StandardNodeSampler sampler = seededSampler(11);
		sampler.setCurrentDifficulty(ConsensusSetup.difficultyForInterval(150_000, total)); // 2.5 min

		int blocks = 20_000;
		double sum = 0;
		for (int b = 0; b < blocks; b++) {
			long first = Long.MAX_VALUE;
			for (double h : hashPower) first = Math.min(first, sampler.getNextMiningInterval(h));
			sum += first;
		}
		double mean = sum / blocks;
		assertEquals(150_000, mean, 150_000 * 0.05, "mean interval " + mean);
	}

	@Test
	void slotLotteryGivesALeaderInAFractionFOfSlots() {
		double[] stakes = {40, 25, 20, 10, 5};
		double total = 100;
		SlotLotteryElection e = new SlotLotteryElection(1000, 0.05, total, seededSampler(1));
		double noLeader = 1;
		for (double s : stakes) noLeader *= 1 - e.leaderProbability(s);
		assertEquals(0.05, 1 - noLeader, 1e-12, "Praos: P(slot has a leader) = f, independent of the split");
		assertTrue(e.leaderProbability(40) < 0.05 * 0.40 + 1e-3, "phi is slightly sub-linear in stake");
	}

	@Test
	void blocksStartOnSlotBoundariesAfterNow() {
		Simulation sim = new Simulation(1);
		NodeStub node = new NodeStub(sim);
		node.setHashPower(30);
		SlotLotteryElection e = new SlotLotteryElection(1000, 0.05, 100, seededSampler(5));
		for (long now : new long[] {0, 1, 999, 1000, 123_456}) {
			for (int i = 0; i < 200; i++) {
				long at = now + e.nextBlockDelay(node, now);
				assertEquals(0, at % 1000, "block at " + at);
				assertTrue(at > now, "block at " + at + " not after " + now);
			}
		}
	}

	@Test
	void meanSlotsToLeadIsOneOverP() {
		StandardNodeSampler sampler = seededSampler(3);
		double p = 0.02;
		int draws = 50_000;
		double sum = 0;
		for (int i = 0; i < draws; i++) {
			long k = sampler.getNextLeaderSlots(p);
			assertTrue(k >= 1);
			sum += k;
		}
		assertEquals(1 / p, sum / draws, (1 / p) * 0.03);
		assertEquals(1, sampler.getNextLeaderSlots(1.0));
		assertEquals(Long.MAX_VALUE, sampler.getNextLeaderSlots(0.0));
	}

	@Test
	void nodeWithoutStakeNeverLeads() {
		Simulation sim = new Simulation(1);
		NodeStub node = new NodeStub(sim);
		node.setHashPower(0);
		SlotLotteryElection e = new SlotLotteryElection(1000, 0.05, 100, seededSampler(5));
		assertTrue(e.nextBlockDelay(node, 0) > 1_000_000_000_000L);
	}

	@Test
	void rejectsNonsenseParameters() {
		assertThrows(IllegalArgumentException.class, () -> new SlotLotteryElection(0, 0.05, 1, seededSampler(1)));
		assertThrows(IllegalArgumentException.class, () -> new SlotLotteryElection(1000, 0, 1, seededSampler(1)));
		assertThrows(IllegalArgumentException.class, () -> new SlotLotteryElection(1000, 1.5, 1, seededSampler(1)));
		assertThrows(IllegalArgumentException.class, () -> new SlotLotteryElection(1000, 0.05, 0, seededSampler(1)));
	}
}
