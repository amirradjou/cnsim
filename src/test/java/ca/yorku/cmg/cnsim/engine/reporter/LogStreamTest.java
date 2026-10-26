package ca.yorku.cmg.cnsim.engine.reporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogStreamTest {

	@TempDir
	Path dir;

	@Test
	void writesHeaderThenLinesInOrder() throws IOException {
		Path f = dir.resolve("log.csv");
		LogStream log = new LogStream(f.toString(), "a, b");
		log.add("1,2");
		log.add("3,4");
		log.close();
		assertEquals(List.of("a, b", "1,2", "3,4"), Files.readAllLines(f));
		assertEquals(2, log.lineCount());
	}

	@Test
	void closingAnUnusedLogLeavesAHeaderOnlyFile() throws IOException {
		Path f = dir.resolve("empty.csv");
		new LogStream(f.toString(), "h").close();
		assertEquals(List.of("h"), Files.readAllLines(f));
		Path g = dir.resolve("noheader.txt");
		new LogStream(g.toString(), null).close();
		assertEquals(0, Files.size(g));
	}

	@Test
	void linesAfterCloseAreAppended() throws IOException {
		Path f = dir.resolve("late.csv");
		LogStream log = new LogStream(f.toString(), "h");
		log.add("x");
		log.close();
		log.add("y");
		log.close();
		assertEquals(List.of("h", "x", "y"), Files.readAllLines(f));
	}

	@Test
	void createsTheDirectoryOnlyWhenTheFirstLineArrives() throws IOException {
		Path run = dir.resolve("out").resolve("2026.10.05 12.00.00");
		LogStream log = new LogStream(run.resolve("BlockLog.csv").toString(), "h");
		assertFalse(Files.exists(run));
		log.add("x");
		log.close();
		assertEquals(List.of("h", "x"), Files.readAllLines(run.resolve("BlockLog.csv")));
	}
}
