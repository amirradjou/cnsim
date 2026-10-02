package ca.yorku.cmg.cnsim.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class ParallelRunnerTest {

	@Test
	void splitsIntoContiguousNearlyEqualSlices() {
		assertEquals(List.of(new ParallelRunner.Slice(1, 4), new ParallelRunner.Slice(5, 3), new ParallelRunner.Slice(8, 3)),
				ParallelRunner.split(1, 10, 3));
		assertEquals(List.of(new ParallelRunner.Slice(11, 1), new ParallelRunner.Slice(12, 1)),
				ParallelRunner.split(11, 2, 8), "never more slices than simulations");
		assertEquals(List.of(new ParallelRunner.Slice(1, 5)), ParallelRunner.split(1, 5, 1));
	}

	@Test
	void childArgumentsReplaceTheSliceOptionsAndKeepTheRest() {
		String[] parent = {"-c", "cfg.properties", "--sims", "30", "-p", "4", "--out", "out/", "--set", "net.topology=small-world",
				"--set", "sim.firstSimID=7", "--set", "sim.parallelism=4"};
		List<String> child = ParallelRunner.childArguments(parent, new ParallelRunner.Slice(9, 8), Path.of("run/slices/1"));
		assertEquals(List.of("-c", "cfg.properties", "--set", "net.topology=small-world", "--sims", "8", "--out",
				"run/slices/1/", "--set", "sim.firstSimID=9", "--set", "sim.parallelism=1"),
				child.stream().map(a -> a.replace('\\', '/')).toList());
	}

	@Test
	void childrenGetAShareOfMemoryUnlessTheHeapIsSet() {
		long gib = 1L << 30;
		List<String> shared = ParallelRunner.childJvmOptions(List.of("-Dfoo=1", "-XX:StartFlightRecording=x.jfr"), 12, 32 * gib);
		assertEquals(List.of("-Dfoo=1", "-Xmx1365m"), shared, "half of 32 GiB over 12 processes, recording dropped");
		assertEquals(List.of("-Xmx256m"), ParallelRunner.childJvmOptions(List.of(), 64, 4 * gib), "at least 256 MB");
		List<String> explicit = ParallelRunner.childJvmOptions(List.of("-Xmx1g"), 12, 32 * gib);
		assertEquals(List.of("-Xmx1g"), explicit);
		assertFalse(ParallelRunner.childJvmOptions(List.of(), 4, -1).stream().anyMatch(o -> o.startsWith("-Xmx")),
				"unknown memory: leave the JVM default");
		assertTrue(ParallelRunner.physicalMemory() != 0);
	}
}
