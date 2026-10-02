package ca.yorku.cmg.cnsim.engine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs the simulations of one run in several processes. The simulation IDs are split into
 * contiguous slices; each slice runs in a child JVM (same JVM options, same arguments, plus
 * {@code --sims}, {@code --out} and {@code sim.firstSimID}), and the children's logs are then
 * concatenated in slice order into the run directory.
 * <p>
 * A simulation's randomness and logs depend only on its ID (seeds are derived from it, and the
 * clock, event, node, transaction and block counters restart with every simulation), so the
 * merged logs are the same as a sequential run's. The golden check verifies this.
 *
 * @author Amirreza Radjou
 */
public final class ParallelRunner {

	/** The logs a run writes, with their file extension. */
	static final List<String> LOGS = List.of("BeliefLog.csv", "BlockLog.csv", "StructureLog.csv", "EventLog.csv",
			"NetLog.csv", "Nodes.csv", "Input.csv", "ErrorLog.txt");

	/** Options the parent sets for each child; any value the user gave is dropped. */
	private static final List<String> REPLACED_OPTIONS = List.of("-p", "--parallel", "-m", "--sims", "--out");
	private static final List<String> REPLACED_KEYS = List.of("sim.parallelism", "sim.firstSimID", "sim.numSimulations",
			"sim.output.directory");

	private ParallelRunner() {
	}

	/** A contiguous range of simulation IDs. */
	record Slice(int firstSimID, int count) {
	}

	/**
	 * Splits {@code count} simulations starting at {@code firstSimID} into at most {@code parts}
	 * contiguous slices whose sizes differ by at most one.
	 */
	static List<Slice> split(int firstSimID, int count, int parts) {
		int n = Math.max(1, Math.min(parts, count));
		List<Slice> slices = new ArrayList<>();
		int next = firstSimID;
		for (int i = 0; i < n; i++) {
			int size = count / n + (i < count % n ? 1 : 0);
			slices.add(new Slice(next, size));
			next += size;
		}
		return slices;
	}

	/** The child's arguments: the parent's, minus the options the parent decides per slice. */
	static List<String> childArguments(String[] args, Slice slice, Path outDir) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			String a = args[i];
			if (REPLACED_OPTIONS.contains(a)) {
				i++; // skip its value
				continue;
			}
			if (a.equals("--set") && i + 1 < args.length) {
				String key = args[i + 1].split("=", 2)[0].trim();
				if (REPLACED_KEYS.contains(key)) {
					i++;
					continue;
				}
			}
			out.add(a);
		}
		out.addAll(List.of("--sims", Integer.toString(slice.count()), "--out", outDir.toString() + File.separator,
				"--set", "sim.firstSimID=" + slice.firstSimID(), "--set", "sim.parallelism=1"));
		return out;
	}

	/**
	 * The parent's JVM options for the children, minus flight recordings and debug agents (they
	 * would clash between processes). Without an explicit heap limit each child gets an even share
	 * of half of physical memory, at least 256 MB: by default every JVM may grow to a quarter of
	 * memory, so a dozen children could exhaust it (pass -Xmx to choose the per-process limit).
	 */
	static List<String> childJvmOptions(List<String> parentOptions, int processes, long physicalBytes) {
		List<String> out = new ArrayList<>();
		boolean heapLimited = false;
		for (String option : parentOptions) {
			if (option.startsWith("-XX:StartFlightRecording") || option.startsWith("-agentlib:jdwp")) {
				continue;
			}
			if (option.startsWith("-Xmx") || option.startsWith("-XX:MaxHeapSize") || option.startsWith("-XX:MaxRAM")) {
				heapLimited = true;
			}
			out.add(option);
		}
		if (!heapLimited && physicalBytes > 0) {
			long share = Math.max(256L << 20, physicalBytes / 2 / Math.max(1, processes));
			out.add("-Xmx" + (share >> 20) + "m");
		}
		return out;
	}

	/** @return Physical memory in bytes, or -1 if the JVM cannot tell. */
	static long physicalMemory() {
		if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
			return os.getTotalMemorySize();
		}
		return -1;
	}

	/**
	 * Runs the slices and merges their logs into {@code runDir}.
	 * @param args The parent's command-line arguments.
	 * @param mainClass The class whose main method runs a simulation batch.
	 * @param runDir The parent's run directory (where the merged logs go).
	 * @param firstSimID The first simulation ID of the run.
	 * @param count The number of simulations.
	 * @param processes How many processes to use.
	 */
	public static void run(String[] args, Class<?> mainClass, Path runDir, int firstSimID, int count, int processes)
			throws IOException, InterruptedException {
		List<Slice> slices = split(firstSimID, count, processes);
		Path slicesDir = runDir.resolve("slices");
		System.out.printf("  * Running %d simulations in %d processes%n", count, slices.size());

		String java = ProcessHandle.current().info().command().orElse("java");
		List<String> jvmOptions = childJvmOptions(ManagementFactory.getRuntimeMXBean().getInputArguments(),
				slices.size(), physicalMemory());
		String classPath = System.getProperty("java.class.path");

		List<Process> children = new ArrayList<>();
		long start = System.currentTimeMillis();
		for (int i = 0; i < slices.size(); i++) {
			Path out = slicesDir.resolve(Integer.toString(i));
			Files.createDirectories(out);
			List<String> command = new ArrayList<>();
			command.add(java);
			command.addAll(jvmOptions);
			command.addAll(List.of("-cp", classPath, mainClass.getName()));
			command.addAll(childArguments(args, slices.get(i), out));
			children.add(new ProcessBuilder(command).redirectErrorStream(true)
					.redirectOutput(out.resolve("console.txt").toFile()).start());
		}

		boolean failed = false;
		for (int i = 0; i < children.size(); i++) {
			int exit = children.get(i).waitFor();
			Slice s = slices.get(i);
			String sims = s.count() == 1 ? "simulation " + s.firstSimID()
					: "simulations " + s.firstSimID() + "-" + (s.firstSimID() + s.count() - 1);
			if (exit == 0) {
				System.out.printf("    %s done (%.1f s)%n", sims, (System.currentTimeMillis() - start) / 1000.0);
			} else {
				failed = true;
				Path console = slicesDir.resolve(Integer.toString(i)).resolve("console.txt");
				System.err.printf("    %s failed (exit %d); output in %s%n", sims, exit, console);
				tail(console, 20).forEach(line -> System.err.println("      " + line));
			}
		}
		if (failed) {
			throw new IllegalStateException("some simulations failed; their output is kept in " + slicesDir);
		}

		String runId = runDir.getFileName().toString();
		for (String log : LOGS) {
			String name = log.substring(0, log.indexOf('.'));
			String ext = log.substring(log.indexOf('.'));
			merge(slicesDir, slices.size(), name, ext, runDir.resolve(name + " - " + runId + ext), ext.equals(".csv"));
		}
		deleteRecursively(slicesDir);
	}

	/** Concatenates one log across the slices, keeping the first header only. */
	private static void merge(Path slicesDir, int slices, String name, String ext, Path target, boolean hasHeader)
			throws IOException {
		try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
			boolean headerWritten = false;
			for (int i = 0; i < slices; i++) {
				Path file = findLog(slicesDir.resolve(Integer.toString(i)), name, ext);
				try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					String line;
					boolean first = true;
					while ((line = r.readLine()) != null) {
						if (first && hasHeader) {
							first = false;
							if (headerWritten) continue;
							headerWritten = true;
						}
						w.write(line);
						w.write(System.lineSeparator());
					}
				}
			}
		}
	}

	/** The '<name> - <run id><ext>' file in the child's single run directory. */
	private static Path findLog(Path sliceDir, String name, String ext) throws IOException {
		try (DirectoryStream<Path> runs = Files.newDirectoryStream(sliceDir, Files::isDirectory)) {
			for (Path run : runs) {
				try (DirectoryStream<Path> logs = Files.newDirectoryStream(run, name + " - *" + ext)) {
					for (Path log : logs) {
						return log;
					}
				}
			}
		}
		throw new IOException("no " + name + " log under " + sliceDir);
	}

	private static List<String> tail(Path file, int lines) {
		try {
			List<String> all = Files.readAllLines(file);
			return all.subList(Math.max(0, all.size() - lines), all.size());
		} catch (IOException e) {
			return List.of("(cannot read " + file + ")");
		}
	}

	private static void deleteRecursively(Path dir) {
		try (Stream<Path> paths = Files.walk(dir)) {
			paths.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.delete(p);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
