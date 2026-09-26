package ca.yorku.cmg.cnsim.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.yorku.cmg.cnsim.engine.network.AbstractNetwork;
import ca.yorku.cmg.cnsim.engine.network.OverlayNetwork;
import ca.yorku.cmg.cnsim.engine.network.Topology;
import ca.yorku.cmg.cnsim.engine.node.NodeSet;
import ca.yorku.cmg.cnsim.engine.node.NodeStub;

/**
 * Relay delays over a peer overlay, and latency on end-to-end networks. Lives in the engine
 * package to save and restore the shared static configuration.
 */
class OverlayNetworkTest {

	private Properties savedProps;
	private boolean savedInitialized;

	@TempDir
	Path dir;

	@BeforeEach
	void setUp() {
		savedProps = new Properties();
		savedProps.putAll(Config.prop);
		savedInitialized = Config.initialized;
		Config.prop.setProperty("sim.maxNodes", "10");
		Config.initialized = true;
	}

	@AfterEach
	void restore() {
		Config.prop.clear();
		Config.prop.putAll(savedProps);
		Config.initialized = savedInitialized;
	}

	private static NodeSet nodes(int n) {
		Simulation sim = new Simulation(1);
		NodeSet ns = new NodeSet(null);
		for (int i = 0; i < n; i++) ns.getNodes().add(new NodeStub(sim));
		return ns;
	}

	private Topology fromLines(int n, String lines) throws IOException {
		Path f = dir.resolve("links.csv");
		Files.writeString(f, lines);
		return Topology.fromFile(f.toString(), n);
	}

	@Test
	void relayPathAddsHopCostsAndIntermediateDelay() throws Exception {
		// 1 Mbps and 10 ms per hop: 1000 bytes take 8 + 10 = 18 ms per hop, plus 5 ms at node 2.
		Topology line = fromLines(3, "1,2,1000000,10\n2,3,1000000,10\n");
		OverlayNetwork net = new OverlayNetwork(nodes(3), line, new Sampler(), 5.0);
		assertEquals(18, net.getPropagationTime(1, 2, 1000));
		assertEquals(41, net.getPropagationTime(1, 3, 1000));
		assertEquals(41, net.getPropagationTime(3, 1, 1000), "links are symmetric");
		assertEquals(2, net.getDegree(2));
	}

	@Test
	void fastestPathDependsOnMessageSize() throws Exception {
		// Direct 1-3 link: 10 kbps, no latency. Detour via 2: 1 Mbps, 10 ms per hop, 5 ms relay.
		Topology t = fromLines(3, "1,2,1000000,10\n2,3,1000000,10\n1,3,10000,0\n");
		OverlayNetwork net = new OverlayNetwork(nodes(3), t, new Sampler(), 5.0);
		assertEquals(41, net.getPropagationTime(1, 3, 1000), "a block goes round the slow link (direct: 800 ms)");
		assertEquals(8, net.getPropagationTime(1, 3, 10), "a small message takes the direct link (detour: 25 ms)");
	}

	@Test
	void rejectsDisconnectedOrMismatchedTopologies() throws Exception {
		Topology split = fromLines(4, "1,2,1000000,1\n3,4,1000000,1\n");
		assertThrows(IllegalArgumentException.class, () -> new OverlayNetwork(nodes(4), split, new Sampler(), 0));
		Topology three = fromLines(3, "1,2,1000000,1\n2,3,1000000,1\n");
		assertThrows(IllegalArgumentException.class, () -> new OverlayNetwork(nodes(4), three, new Sampler(), 0));
	}

	@Test
	void endToEndLatencyIsAddedToTransmissionTime() throws Exception {
		AbstractNetwork net = new AbstractNetwork(nodes(2)) {};
		net.Net[1][2] = 1_000_000f;
		assertEquals(8, net.getPropagationTime(1, 2, 1000), "no latency configured");
		net.setLatency(1, 2, 42.4f);
		assertEquals(50, net.getPropagationTime(1, 2, 1000));
		assertEquals(0f, net.getLatency(2, 1));
	}

	@Test
	void zeroLatencyLeavesTheNetworkWithoutLatencies() throws Exception {
		AbstractNetwork net = new AbstractNetwork(nodes(2)) {};
		net.Net[1][2] = 1_000_000f;
		net.setLatency(1, 2, 0f);
		assertEquals(8, net.getPropagationTime(1, 2, 1000));
		assertThrows(ArithmeticException.class, () -> net.setLatency(1, 2, -1f));
	}
}
