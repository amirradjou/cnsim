package ca.yorku.cmg.cnsim.engine.reporter;

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import ca.yorku.cmg.cnsim.engine.Config;

/**
 * Records what produced a run: the CNSim build (commit and whether the working tree had
 * uncommitted changes, from the git.properties packaged into the jar), the Java runtime, the
 * command line, and SHA-256 hashes of the configuration file and of every input file it names.
 * Written as {@code Provenance - <run id>.json} next to the logs, so a results folder can be
 * traced back to exact code and inputs.
 *
 * @author Amirreza Radjou
 */
public final class Provenance {

	/** Config keys that name input files. */
	private static final List<String> INPUT_KEYS = List.of(
			"node.sampler.file", "workload.sampler.file", "net.sampler.file", "net.topology.file");

	private static final Properties BUILD = loadBuildInfo();

	private Provenance() {
	}

	private static Properties loadBuildInfo() {
		Properties p = new Properties();
		try (InputStream in = Provenance.class.getResourceAsStream("/git.properties")) {
			if (in != null) {
				p.load(in);
			}
		} catch (IOException e) {
			// Built without git metadata: leave the fields unknown.
		}
		return p;
	}

	/** @return e.g. "0.0.1-SNAPSHOT (6d2ef6b, uncommitted changes)", or "unknown build". */
	public static String describeBuild() {
		String version = BUILD.getProperty("git.build.version");
		String commit = BUILD.getProperty("git.commit.id.abbrev");
		if (version == null && commit == null) {
			return "unknown build";
		}
		String s = (version == null ? "" : version + " ") + "(" + (commit == null ? "unknown commit" : commit);
		if ("true".equals(BUILD.getProperty("git.dirty"))) {
			s += ", uncommitted changes";
		}
		return s + ")";
	}

	/**
	 * Writes the provenance file into the run's output directory.
	 * @param args The command-line arguments of the run.
	 * @param startedAt When the run started.
	 */
	public static void write(String[] args, Instant startedAt) {
		Instant finishedAt = Instant.now();
		StringBuilder j = new StringBuilder("{\n");
		j.append("  \"runId\": ").append(str(Reporter.getRunId())).append(",\n");
		j.append("  \"started\": ").append(str(startedAt.toString())).append(",\n");
		j.append("  \"finished\": ").append(str(finishedAt.toString())).append(",\n");
		j.append("  \"wallClockMillis\": ").append(finishedAt.toEpochMilli() - startedAt.toEpochMilli()).append(",\n");
		j.append("  \"cnsim\": {\n");
		j.append("    \"version\": ").append(str(BUILD.getProperty("git.build.version"))).append(",\n");
		j.append("    \"commit\": ").append(str(BUILD.getProperty("git.commit.id"))).append(",\n");
		j.append("    \"commitTime\": ").append(str(BUILD.getProperty("git.commit.time"))).append(",\n");
		j.append("    \"uncommittedChanges\": ").append(BUILD.getProperty("git.dirty", "null")).append("\n");
		j.append("  },\n");
		j.append("  \"java\": {\"version\": ").append(str(System.getProperty("java.version")))
				.append(", \"vendor\": ").append(str(System.getProperty("java.vendor"))).append("},\n");
		j.append("  \"os\": ").append(str(System.getProperty("os.name") + " " + System.getProperty("os.version")))
				.append(",\n");
		j.append("  \"commandLine\": [");
		for (int i = 0; i < args.length; i++) {
			j.append(i == 0 ? "" : ", ").append(str(args[i]));
		}
		j.append("],\n");
		j.append("  \"config\": ").append(fileEntry(Config.getPropertyString("config.file"))).append(",\n");
		j.append("  \"inputs\": {");
		boolean first = true;
		for (String key : INPUT_KEYS) {
			String path = Config.getPropertyString(key);
			if (path == null || path.isBlank()) continue;
			j.append(first ? "\n" : ",\n").append("    ").append(str(key)).append(": ").append(fileEntry(path));
			first = false;
		}
		j.append(first ? "},\n" : "\n  },\n");
		String sims = Config.getPropertyString("sim.numSimulations");
		j.append("  \"simulations\": ").append(sims != null && sims.trim().matches("\\d+") ? sims.trim() : str(sims)).append("\n");
		j.append("}\n");

		String file = Reporter.path + "Provenance - " + Reporter.getRunId() + ".json";
		try (FileWriter w = new FileWriter(file)) {
			w.write(j.toString());
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write " + file, e);
		}
	}

	private static String fileEntry(String path) {
		if (path == null) {
			return "null";
		}
		Path p = Paths.get(path.trim());
		String hash = Files.isRegularFile(p) ? sha256(p) : null;
		return "{\"path\": " + str(p.toAbsolutePath().normalize().toString()) + ", \"sha256\": " + str(hash) + "}";
	}

	static String sha256(Path p) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(md.digest(Files.readAllBytes(p)));
		} catch (IOException e) {
			return null;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** A JSON string literal, or null. */
	static String str(String s) {
		if (s == null) {
			return "null";
		}
		StringBuilder b = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				default -> {
					if (c < 0x20) {
						b.append(String.format("\\u%04x", (int) c));
					} else {
						b.append(c);
					}
				}
			}
		}
		return b.append('"').toString();
	}
}
