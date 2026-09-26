package ca.yorku.cmg.cnsim.engine.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TopologyTest {

	@Test
	void completeGraphLinksEveryPair() {
		Topology t = Topology.complete(6);
		assertEquals(15, t.linkCount());
		assertTrue(t.isConnected());
		for (int i = 1; i <= 6; i++) assertEquals(5, t.degree(i));
	}

	@Test
	void randomOutboundGivesEveryNodeAtLeastItsOutboundPeers() {
		Topology t = Topology.randomOutbound(50, 8, new Random(1));
		for (int i = 1; i <= 50; i++) {
			assertTrue(t.degree(i) >= 8, "node " + i + " has degree " + t.degree(i));
		}
		// Each node opens 8; duplicates only merge, so between 50*8/2 and 50*8 links.
		assertTrue(t.linkCount() >= 200 && t.linkCount() <= 400);
		assertTrue(t.isConnected());
	}

	@Test
	void randomOutboundCapsDegreeInSmallNetworks() {
		Topology t = Topology.randomOutbound(4, 8, new Random(1));
		assertEquals(6, t.linkCount(), "with 4 nodes every node links to all 3 others");
	}

	@Test
	void randomRegularGivesEveryNodeExactlyTheDegree() {
		for (long seed = 1; seed <= 10; seed++) {
			Topology t = Topology.randomRegular(30, 8, new Random(seed));
			for (int i = 1; i <= 30; i++) assertEquals(8, t.degree(i));
			assertEquals(120, t.linkCount());
		}
	}

	@Test
	void randomRegularRejectsImpossibleParameters() {
		assertThrows(IllegalArgumentException.class, () -> Topology.randomRegular(5, 3, new Random(1)), "odd n*k");
		assertThrows(IllegalArgumentException.class, () -> Topology.randomRegular(5, 5, new Random(1)), "k >= n");
	}

	@Test
	void generatorsAreDeterministicForASeed() {
		assertEquals(Topology.scaleFree(40, 2, new Random(9)).links(), Topology.scaleFree(40, 2, new Random(9)).links());
		assertEquals(Topology.smallWorld(40, 4, 0.2, new Random(9)).links(), Topology.smallWorld(40, 4, 0.2, new Random(9)).links());
		assertEquals(Topology.erdosRenyi(40, 0.1, new Random(9)).links(), Topology.erdosRenyi(40, 0.1, new Random(9)).links());
	}

	@Test
	void smallWorldWithoutRewiringIsARingLattice() {
		Topology t = Topology.smallWorld(10, 4, 0.0, new Random(1));
		for (int i = 1; i <= 10; i++) assertEquals(4, t.degree(i));
		assertTrue(t.hasLink(1, 2) && t.hasLink(1, 3) && t.hasLink(10, 1) && t.hasLink(9, 1));
	}

	@Test
	void smallWorldRewiringKeepsTheLinkCount() {
		Topology t = Topology.smallWorld(100, 6, 0.5, new Random(3));
		assertEquals(300, t.linkCount());
	}

	@Test
	void scaleFreeHasHubs() {
		Topology t = Topology.scaleFree(300, 2, new Random(4));
		int max = 0;
		for (int i = 1; i <= 300; i++) max = Math.max(max, t.degree(i));
		assertTrue(t.isConnected());
		assertTrue(max >= 15, "preferential attachment should grow a hub, max degree was " + max);
	}

	@Test
	void connectComponentsJoinsStrayNodes() {
		Topology t = Topology.erdosRenyi(30, 0.0, new Random(1));
		assertFalse(t.isConnected());
		assertEquals(29, t.connectComponents(new Random(2)));
		assertTrue(t.isConnected());
	}

	@Test
	void readsLinksWithOptionalPropertiesFromAFile(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("links.csv");
		Files.writeString(f, "from,to,throughput,latency\n1,2,1000000,40\n2,3\n# comment\n3,1,2000000,\n");
		Topology t = Topology.fromFile(f.toString(), 3);
		assertEquals(3, t.linkCount());
		Topology.Link first = t.links().get(0);
		assertEquals(1000000f, first.throughput());
		assertEquals(40f, first.latency());
		assertTrue(Float.isNaN(t.links().get(1).throughput()));
		assertTrue(Float.isNaN(t.links().get(2).latency()));
	}

	@Test
	void rejectsBadLinkFiles(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("bad.csv");
		Files.writeString(f, "1,9\n");
		assertThrows(IOException.class, () -> Topology.fromFile(f.toString(), 3));
		Files.writeString(f, "1,2,-5\n");
		assertThrows(IOException.class, () -> Topology.fromFile(f.toString(), 3));
	}
}
