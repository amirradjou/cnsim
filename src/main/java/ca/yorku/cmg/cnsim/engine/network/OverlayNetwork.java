package ca.yorku.cmg.cnsim.engine.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

import ca.yorku.cmg.cnsim.engine.Sampler;
import ca.yorku.cmg.cnsim.engine.node.NodeSet;

/**
 * A peer-to-peer overlay network. Nodes are linked only to their peers (see {@link Topology});
 * every link has its own throughput and latency, and messages travel hop by hop, each relay
 * receiving the whole message before forwarding it (store and forward) after a fixed
 * {@code hopDelay}.
 * <p>
 * {@link #getPropagationTime(int, int, float)} returns the delay of the fastest relay path. That is
 * exactly when a message flooded from the origin first reaches the destination if every node
 * forwards what it receives to all its peers, so the engine's "send to every node" propagation
 * reproduces gossip without scheduling the intermediate hops. The model assumes every node relays
 * (an attacker that withholds its own blocks still forwards other nodes' messages).
 *
 * @author Amirreza Radjou
 */
public class OverlayNetwork extends AbstractNetwork {

	private record Edge(int to, double throughput, double latency) {}

	private final List<List<Edge>> adjacency = new ArrayList<>();
	private final double hopDelay;
	private final Topology topology;

	// Every node propagates to all others with the same origin and size in a row; keep the last
	// shortest-path result.
	private int cachedOrigin = -1;
	private float cachedSize = Float.NaN;
	private double[] cachedDelay;

	/**
	 * @param ns The nodes; their IDs must be 1..n where n is the size of the topology.
	 * @param topology The links. Links without a throughput or latency (all generated ones) get
	 *        them from the network sampler: throughput from net.throughputMean/SD, latency from
	 *        net.latencyMean/SD.
	 * @param sampler The sampler whose network sampler provides link properties.
	 * @param hopDelay Milliseconds each intermediate node spends before forwarding.
	 * @throws Exception If the topology does not match the node set or is not connected.
	 */
	public OverlayNetwork(NodeSet ns, Topology topology, Sampler sampler, double hopDelay) throws Exception {
		super(ns);
		if (topology.size() != ns.getNodeSetCount()) {
			throw new IllegalArgumentException("Topology has " + topology.size() + " nodes but the node set has "
					+ ns.getNodeSetCount());
		}
		if (!topology.isConnected()) {
			throw new IllegalArgumentException("Network topology is not connected: some nodes could never receive blocks");
		}
		if (hopDelay < 0) {
			throw new IllegalArgumentException("net.topology.hopDelay must be >= 0, got " + hopDelay);
		}
		this.topology = topology;
		this.hopDelay = hopDelay;
		for (int i = 0; i <= topology.size(); i++) {
			adjacency.add(new ArrayList<>());
		}
		for (Topology.Link l : topology.links()) {
			float bps = Float.isNaN(l.throughput())
					? sampler.getNetworkSampler().getNextConnectionThroughput() : l.throughput();
			float lat = Float.isNaN(l.latency())
					? sampler.getNetworkSampler().getNextConnectionLatency() : l.latency();
			setThroughput(l.a(), l.b(), bps);
			setThroughput(l.b(), l.a(), bps);
			setLatency(l.a(), l.b(), lat);
			setLatency(l.b(), l.a(), lat);
			adjacency.get(l.a()).add(new Edge(l.b(), bps, lat));
			adjacency.get(l.b()).add(new Edge(l.a(), bps, lat));
		}
	}

	public Topology getTopology() {
		return topology;
	}

	/**
	 * @return The number of peers of a node.
	 */
	public int getDegree(int node) {
		return adjacency.get(node).size();
	}

	/**
	 * Delay (ms, rounded) of the fastest relay path for a message of {@code size} bytes. Each hop
	 * costs latency plus transmission time (size x 8000 / throughput); each intermediate node adds
	 * {@code hopDelay}.
	 */
	@Override
	public long getPropagationTime(int origin, int destination, float size) {
		if (size < 0) {
			throw new ArithmeticException("Size < 0");
		}
		if (origin != cachedOrigin || Float.compare(size, cachedSize) != 0) {
			cachedDelay = shortestDelays(origin, size);
			cachedOrigin = origin;
			cachedSize = size;
		}
		return Math.round(cachedDelay[destination]);
	}

	private double[] shortestDelays(int origin, float size) {
		double[] dist = new double[adjacency.size()];
		Arrays.fill(dist, Double.POSITIVE_INFINITY);
		dist[origin] = 0;
		PriorityQueue<double[]> queue = new PriorityQueue<>((x, y) -> Double.compare(x[0], y[0]));
		queue.add(new double[] {0, origin});
		double bits = size * 8.0 * 1000.0;
		while (!queue.isEmpty()) {
			double[] head = queue.poll();
			int u = (int) head[1];
			if (head[0] > dist[u]) continue;
			double leave = dist[u] + (u == origin ? 0 : hopDelay);
			for (Edge e : adjacency.get(u)) {
				double arrive = leave + e.latency() + bits / e.throughput();
				if (arrive < dist[e.to()]) {
					dist[e.to()] = arrive;
					queue.add(new double[] {arrive, e.to()});
				}
			}
		}
		return dist;
	}

	/**
	 * Average throughput of a node's links (the base class averages over every other node, which
	 * would count missing links as zero).
	 */
	@Override
	public float getAvgTroughput(int origin) {
		List<Edge> edges = adjacency.get(origin);
		if (edges.isEmpty()) return 0;
		double sum = 0;
		for (Edge e : edges) sum += e.throughput();
		return (float) (sum / edges.size());
	}
}
