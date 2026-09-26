package ca.yorku.cmg.cnsim.engine.reporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProvenanceTest {

	@TempDir
	Path dir;

	@Test
	void provenanceEscapesJsonStringsAndHashesFiles() throws IOException {
		assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", Provenance.str("a\"b\\c\n\u0001"));
		assertEquals("null", Provenance.str(null));
		Path f = dir.resolve("abc.txt");
		Files.writeString(f, "abc");
		assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Provenance.sha256(f));
		assertNull(Provenance.sha256(dir.resolve("missing")));
	}
}
