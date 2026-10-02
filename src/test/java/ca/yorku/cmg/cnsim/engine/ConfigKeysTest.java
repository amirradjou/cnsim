package ca.yorku.cmg.cnsim.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class ConfigKeysTest {

	@Test
	void suggestsTheKeyATypoWasMeantToBe() {
		assertEquals("workload.targetTransaction", ConfigKeys.closest("worlkoad.targetTransaction"));
		assertEquals("net.topology.degree", ConfigKeys.closest("net.topology.degre"));
		assertNull(ConfigKeys.closest("something.entirely.different"));
	}

	@Test
	void reportsUnknownKeysAndGroupsLegacyOnes() {
		List<String> messages = ConfigKeys.report(Set.of("sim.numSimulations", "worlkoad.targetTransaction",
				"net.propagationTime", "tangle.alpha", "tangle.numOfFounders", "mystery"));
		assertEquals(List.of(
				"Warning: unknown configuration key 'mystery' has no effect.",
				"Warning: unknown configuration key 'worlkoad.targetTransaction' has no effect (did you mean 'workload.targetTransaction'?).",
				"Note: these configuration keys have no effect in this version: net.propagationTime, tangle.*."), messages);
		assertTrue(ConfigKeys.report(Set.of("sim.numSimulations", "pow.difficulty")).isEmpty());
	}

	/** Every key the code reads is registered, so valid configs never get a warning. */
	@Test
	void everyKeyReadInTheCodeIsKnown() throws IOException {
		Pattern read = Pattern.compile("Config\\.(?:getProperty[A-Za-z]*|hasProperty)\\(\"([a-zA-Z.]+)\"");
		Pattern constant = Pattern.compile("_KEY\\s*=\\s*\"([a-zA-Z.]+)\"");
		Set<String> used = new TreeSet<>();
		try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
			for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				// Commented-out code does not read anything.
				String code = Files.readString(f).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
				for (Pattern p : List.of(read, constant)) {
					Matcher m = p.matcher(code);
					while (m.find()) used.add(m.group(1));
				}
			}
		}
		used.removeIf(k -> !k.contains(".") || k.equals("config.txt"));
		used.removeAll(ConfigKeys.KNOWN);
		assertEquals(Set.of(), used, "keys read by the code but missing from ConfigKeys.KNOWN");
	}

	@Test
	void everyKnownKeyIsDocumented() throws IOException {
		String doc = Files.readString(Path.of("docs/configuration.md"));
		Set<String> missing = new TreeSet<>();
		for (String key : ConfigKeys.KNOWN) {
			if (!key.equals("config.file") && !doc.contains("`" + key + "`")) missing.add(key);
		}
		assertEquals(Set.of(), missing, "keys missing from docs/configuration.md");
	}

	@Test
	void editDistance() {
		assertEquals(0, ConfigKeys.distance("abc", "abc"));
		assertEquals(2, ConfigKeys.distance("worlkoad", "workload"));
		assertEquals(3, ConfigKeys.distance("kitten", "sitting"));
	}
}
