package ca.yorku.cmg.cnsim.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Per-simulation seeds must give each simulation a stream of its own from the first draw. */
class PerSimulationSeedTest {

	private static final int SIMS = 400;

	private Properties savedProps;
	private boolean savedInitialized;

	@BeforeEach
	void saveConfig() {
		savedProps = new Properties();
		savedProps.putAll(Config.prop);
		savedInitialized = Config.initialized;
		Config.prop.remove(SeedManager.DERIVATION_KEY);
		Config.initialized = true;
	}

	@AfterEach
	void restoreConfig() {
		Config.prop.clear();
		Config.prop.putAll(savedProps);
		Config.initialized = savedInitialized;
	}

	/** How many of the simulations' first draws fall in each tenth of [0, 1). */
	private static int[] firstDrawDeciles(java.util.function.IntToLongFunction seedOf) {
		int[] counts = new int[10];
		for (int sim = 1; sim <= SIMS; sim++) {
			counts[(int) (new Random(seedOf.applyAsLong(sim)).nextDouble() * 10)]++;
		}
		return counts;
	}

	@Test
	void consecutiveSeedsGaveNearlyTheSameFirstDraw() {
		// Why base + simID was replaced: every first draw lands in [0.6, 0.8).
		int[] old = firstDrawDeciles(sim -> 222 + sim);
		assertEquals(SIMS, old[6] + old[7]);
	}

	@Test
	void scrambledSeedsSpreadTheFirstDraws() {
		int[] counts = firstDrawDeciles(sim -> SeedManager.perSimulation(222, sim));
		for (int c : counts) {
			assertTrue(c >= 20 && c <= 60, "40 expected per tenth, got " + java.util.Arrays.toString(counts));
		}
	}

	@Test
	void seedsDependOnBaseAndSimulationOnly() {
		assertEquals(SeedManager.perSimulation(444, 7), SeedManager.perSimulation(444, 7));
		assertNotEquals(SeedManager.perSimulation(444, 7), SeedManager.perSimulation(444, 8));
		assertNotEquals(SeedManager.perSimulation(444, 7), SeedManager.perSimulation(445, 7));
	}

	@Test
	void onlyFlaggedSeedsAreDerived() {
		SeedManager m = new SeedManager(new long[] {444, 222}, new boolean[] {false, true}, 5);
		Random r = new Random();
		m.updateSeed(r);
		assertEquals(new Random(444).nextLong(), r.nextLong(), "an unflagged seed is used as given");
		m.updateSeed(r);
		assertEquals(new Random(SeedManager.perSimulation(222, 5)).nextLong(), r.nextLong());
	}

	@Test
	void legacyDerivationKeepsTheThesisSeeds() {
		assertEquals(SeedManager.perSimulation(222, 3), SeedManager.derive(222, 3), "scrambled by default");
		Config.prop.setProperty(SeedManager.DERIVATION_KEY, "legacy");
		assertEquals(225, SeedManager.derive(222, 3));
		Config.prop.setProperty(SeedManager.DERIVATION_KEY, "sequential");
		org.junit.jupiter.api.Assertions.assertThrows(ConfigException.class, () -> SeedManager.derive(222, 3));
	}
}
