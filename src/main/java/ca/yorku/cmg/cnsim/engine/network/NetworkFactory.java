package ca.yorku.cmg.cnsim.engine.network;

import java.util.Random;

import ca.yorku.cmg.cnsim.engine.Config;
import ca.yorku.cmg.cnsim.engine.Sampler;
import ca.yorku.cmg.cnsim.engine.node.NodeSet;

/**
 * Builds the network for a simulation from the configuration.
 * <ul>
 * <li>{@code net.sampler.file} set: end-to-end throughputs read from that file.</li>
 * <li>{@code net.topology} absent or {@code complete}: every pair of nodes directly connected
 * with a sampled end-to-end throughput (the original CNSim model), plus optional latency.</li>
 * <li>{@code net.topology} one of {@code random-outbound}, {@code random-regular},
 * {@code erdos-renyi}, {@code small-world}, {@code scale-free}, {@code file}: a peer-to-peer
 * overlay ({@link OverlayNetwork}) where messages are relayed hop by hop.</li>
 * </ul>
 * Overlay keys: {@code net.topology.degree} (default 8), {@code net.topology.edgeProbability},
 * {@code net.topology.rewireProbability} (default 0.1), {@code net.topology.file},
 * {@code net.topology.hopDelay} (ms, default 0). Link latency for any sampled network:
 * {@code net.latencyMean}, {@code net.latencySD} (ms, default 0).
 */
public class NetworkFactory {

	public static final String TOPOLOGY_KEY = "net.topology";

	public static AbstractNetwork createNetwork(NodeSet ns, Sampler sampler) throws Exception {
		String netFilePath = Config.getPropertyString("net.sampler.file");
		String kind = topologyKind();

		if (netFilePath != null) {
			if (!kind.equals("complete")) {
				throw new IllegalArgumentException("net.sampler.file gives an end-to-end throughput matrix and cannot be combined with "
						+ TOPOLOGY_KEY + " = " + kind + "; for a peer graph from a file use " + TOPOLOGY_KEY
						+ " = file and net.topology.file");
			}
			return new FileBasedEndToEndNetwork(ns, netFilePath);
		}
		if (kind.equals("complete")) {
			return new RandomEndToEndNetwork(ns, sampler);
		}
		Topology topology = buildTopology(kind, ns.getNodeSetCount(), sampler.getNetworkSampler().getRandom());
		double hopDelay = Config.getPropertyFloat("net.topology.hopDelay", 0f);
		return new OverlayNetwork(ns, topology, sampler, hopDelay);
	}

	static String topologyKind() {
		String kind = Config.getPropertyString(TOPOLOGY_KEY);
		return (kind == null || kind.isBlank()) ? "complete" : kind.trim().toLowerCase();
	}

	/**
	 * Builds the peer graph named by {@code kind}. Generated graphs that come out disconnected
	 * (possible for erdos-renyi and small-world) get one extra link per stray component.
	 */
	static Topology buildTopology(String kind, int n, Random rnd) throws Exception {
		int degree = Config.hasProperty("net.topology.degree") ? Config.getPropertyInt("net.topology.degree") : 8;
		Topology t;
		switch (kind) {
			case "random-outbound":
				t = Topology.randomOutbound(n, degree, rnd);
				break;
			case "random-regular":
				t = Topology.randomRegular(n, degree, rnd);
				break;
			case "erdos-renyi":
				t = Topology.erdosRenyi(n, Config.getPropertyDouble("net.topology.edgeProbability"), rnd);
				break;
			case "small-world":
				double rewire = Config.hasProperty("net.topology.rewireProbability")
						? Config.getPropertyDouble("net.topology.rewireProbability") : 0.1;
				t = Topology.smallWorld(n, degree, rewire, rnd);
				break;
			case "scale-free":
				t = Topology.scaleFree(n, degree, rnd);
				break;
			case "file":
				String path = Config.getPropertyString("net.topology.file");
				if (path == null) {
					throw new IllegalArgumentException(TOPOLOGY_KEY + " = file needs net.topology.file");
				}
				return Topology.fromFile(path, n); // must be connected as given
			default:
				throw new IllegalArgumentException("Unknown " + TOPOLOGY_KEY + " '" + kind + "'. Use one of: complete, "
						+ "random-outbound, random-regular, erdos-renyi, small-world, scale-free, file");
		}
		int added = t.connectComponents(rnd);
		if (added > 0) {
			System.out.println("    Network: added " + added + " link(s) to join a disconnected " + kind + " graph");
		}
		return t;
	}
}
