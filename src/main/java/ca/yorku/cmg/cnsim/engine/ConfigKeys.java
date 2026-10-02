package ca.yorku.cmg.cnsim.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The configuration keys CNSim reads, and the legacy keys that old configs still carry but that
 * have no effect. A key in a config that is in neither set is most likely a typo: before, it was
 * silently ignored (the thesis configs set {@code worlkoad.targetTransaction}). Every key is
 * documented in docs/configuration.md; ConfigKeysTest keeps this list, the code and the
 * documentation in step.
 */
public final class ConfigKeys {

	private ConfigKeys() {
	}

	/** Keys read by the simulator. */
	public static final Set<String> KNOWN = Set.of(
			"config.file",
			"sim.numSimulations", "sim.terminate.atTime", "sim.maxNodes", "sim.output.directory",
			"sim.reporting.beliefReportInterval", "sim.reporting.beliefReportOffset", "sim.reporting.window",
			"sim.initialMiningPoolTransactions", "sim.parallelism", "sim.firstSimID",
			"reporter.reportEvents", "reporter.reportTransactions", "reporter.reportNodes", "reporter.reportNetEvents",
			"reporter.reportBeliefs", "reporter.reportBlockEvents", "reporter.reportStructureEvents",
			"net.numOfNodes", "net.numOfHonestNodes", "net.numOfMaliciousNodes", "net.numOfSelfishNodes",
			"net.sampler.file", "net.sampler.seed", "net.sampler.seed.updateSeed", "net.throughputMean",
			"net.throughputSD", "net.latencyMean", "net.latencySD", "net.topology", "net.topology.degree",
			"net.topology.edgeProbability", "net.topology.file", "net.topology.hopDelay",
			"net.topology.rewireProbability",
			"node.sampler.file", "node.sampler.seed", "node.sampler.seedUpdateTimes", "node.sampler.updateSeedFlags",
			"node.electricPowerMean", "node.electricPowerSD", "node.electricCostMean", "node.electricCostSD",
			"node.maliciousPowerByRatio", "node.maliciousRatio", "node.maliciousHashPower", "node.selfishRatio",
			"node.selfishHashPower",
			"pow.difficulty", "pow.hashPowerMean", "pow.hashPowerSD", "pow.targetBlockInterval",
			"pos.slotDuration", "pos.activeSlotCoefficient", "consensus.leaderElection", "consensus.tieBreak",
			"workload.lambda", "workload.numTransactions", "workload.sampleTransaction", "workload.targetTransaction",
			"workload.txSizeMean", "workload.txSizeSD", "workload.txFeeValueMean", "workload.txFeeValueSD",
			"workload.sampler.file", "workload.sampler.seed", "workload.sampler.seed.updateSeed",
			"workload.sampler.seed.updateTransaction",
			"bitcoin.maxBlockSize", "bitcoin.minValueToMine", "bitcoin.minSizeToMine",
			"bitcoin.blockProcessingDelayPerByte", "bitcoin.txProcessingDelayPerByte",
			"bitcoin.reorg.restoreTransactions", "bitcoin.attack.minChainLength", "bitcoin.attack.maxChainLength");

	/** Keys of earlier versions that are accepted but have no effect. */
	public static final Set<String> NO_EFFECT = Set.of(
			"net.propagationTime", "sim.maxTransactions", "node.createMaliciousNode", "sim.reportingWindow",
			"sim.numofSim", "sampler.useFileBasedSampler");

	/** Prefixes of keys for modules that are not part of this simulator (the Tangle). */
	private static final List<String> NO_EFFECT_PREFIXES = List.of("tangle.");

	static boolean hasNoEffect(String key) {
		return NO_EFFECT.contains(key) || NO_EFFECT_PREFIXES.stream().anyMatch(key::startsWith);
	}

	/**
	 * Messages about the keys of a configuration that the simulator does not use: one warning
	 * per unknown key, with the closest known key when it looks like a typo, and one note listing
	 * the legacy keys that have no effect.
	 */
	public static List<String> report(Set<String> keys) {
		List<String> messages = new ArrayList<>();
		Set<String> legacy = new TreeSet<>();
		for (String key : new TreeSet<>(keys)) {
			if (KNOWN.contains(key)) {
				continue;
			}
			if (hasNoEffect(key)) {
				legacy.add(key.startsWith("tangle.") ? "tangle.*" : key);
				continue;
			}
			String suggestion = closest(key);
			messages.add("Warning: unknown configuration key '" + key + "' has no effect"
					+ (suggestion == null ? "." : " (did you mean '" + suggestion + "'?)."));
		}
		if (!legacy.isEmpty()) {
			messages.add("Note: these configuration keys have no effect in this version: " + String.join(", ", legacy) + ".");
		}
		return messages;
	}

	/** The known key closest to {@code key} by edit distance, if within 3 edits. */
	static String closest(String key) {
		String best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (String known : new TreeSet<>(KNOWN)) {
			int d = distance(key.toLowerCase(), known.toLowerCase());
			if (d < bestDistance) {
				bestDistance = d;
				best = known;
			}
		}
		return bestDistance <= 3 ? best : null;
	}

	/** Levenshtein distance. */
	static int distance(String a, String b) {
		int[] previous = new int[b.length() + 1];
		int[] current = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) previous[j] = j;
		for (int i = 1; i <= a.length(); i++) {
			current[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
				current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return previous[b.length()];
	}
}
