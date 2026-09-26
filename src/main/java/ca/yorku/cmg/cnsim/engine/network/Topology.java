package ca.yorku.cmg.cnsim.engine.network;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * An undirected peer graph over nodes {@code 1..n}, and the generators for the overlay shapes
 * CNSim supports. Links keep insertion order, so a generator run with the same {@link Random}
 * state always yields the same graph and, downstream, the same link properties.
 *
 * @author Amirreza Radjou
 */
public final class Topology {

	/** An undirected link. Throughput (bps) and latency (ms) are NaN when not given by a file. */
	public record Link(int a, int b, float throughput, float latency) {}

	private final int n;
	private final Map<Long, Link> links = new LinkedHashMap<>();

	public Topology(int n) {
		if (n < 1) {
			throw new IllegalArgumentException("A topology needs at least one node, got " + n);
		}
		this.n = n;
	}

	public int size() {
		return n;
	}

	public List<Link> links() {
		return new ArrayList<>(links.values());
	}

	public int linkCount() {
		return links.size();
	}

	public boolean hasLink(int a, int b) {
		return links.containsKey(key(a, b));
	}

	/**
	 * Adds a link unless it is a self-loop or already present.
	 * @return {@code true} if the link was added.
	 */
	public boolean addLink(int a, int b) {
		return addLink(a, b, Float.NaN, Float.NaN);
	}

	private boolean addLink(int a, int b, float throughput, float latency) {
		checkNode(a);
		checkNode(b);
		if (a == b || hasLink(a, b)) {
			return false;
		}
		links.put(key(a, b), new Link(Math.min(a, b), Math.max(a, b), throughput, latency));
		return true;
	}

	public int degree(int node) {
		int d = 0;
		for (Link l : links.values()) {
			if (l.a() == node || l.b() == node) d++;
		}
		return d;
	}

	private void checkNode(int id) {
		if (id < 1 || id > n) {
			throw new IllegalArgumentException("Node ID " + id + " outside 1.." + n);
		}
	}

	private static long key(int a, int b) {
		int lo = Math.min(a, b), hi = Math.max(a, b);
		return ((long) lo << 32) | hi;
	}

	//
	// Connectivity
	//

	/** @return the component label (smallest member ID) of every node, indexed 1..n. */
	int[] components() {
		int[] parent = new int[n + 1];
		for (int i = 1; i <= n; i++) parent[i] = i;
		for (Link l : links.values()) {
			int ra = find(parent, l.a()), rb = find(parent, l.b());
			if (ra != rb) parent[Math.max(ra, rb)] = Math.min(ra, rb);
		}
		int[] label = new int[n + 1];
		for (int i = 1; i <= n; i++) label[i] = find(parent, i);
		return label;
	}

	private static int find(int[] parent, int x) {
		while (parent[x] != x) {
			parent[x] = parent[parent[x]];
			x = parent[x];
		}
		return x;
	}

	public boolean isConnected() {
		int[] label = components();
		for (int i = 1; i <= n; i++) {
			if (label[i] != 1) return false;
		}
		return true;
	}

	/**
	 * Joins every component to the one containing node 1 with a single link between randomly
	 * chosen members, so that every node is reachable. Returns the number of links added.
	 */
	public int connectComponents(Random rnd) {
		int added = 0;
		int[] label = components();
		List<Integer> main = new ArrayList<>();
		Map<Integer, List<Integer>> others = new LinkedHashMap<>();
		for (int i = 1; i <= n; i++) {
			if (label[i] == label[1]) {
				main.add(i);
			} else {
				others.computeIfAbsent(label[i], k -> new ArrayList<>()).add(i);
			}
		}
		for (List<Integer> comp : others.values()) {
			int a = main.get(rnd.nextInt(main.size()));
			int b = comp.get(rnd.nextInt(comp.size()));
			addLink(a, b);
			main.addAll(comp);
			added++;
		}
		return added;
	}

	//
	// Generators
	//

	/** Every pair of nodes linked. */
	public static Topology complete(int n) {
		Topology t = new Topology(n);
		for (int i = 1; i <= n; i++) {
			for (int j = i + 1; j <= n; j++) {
				t.addLink(i, j);
			}
		}
		return t;
	}

	/**
	 * Every node opens {@code outbound} links to distinct peers chosen uniformly at random; links
	 * are bidirectional, so the average degree is close to {@code 2 * outbound}. This is how
	 * Bitcoin Core forms its overlay (8 outbound full-relay connections by default).
	 */
	public static Topology randomOutbound(int n, int outbound, Random rnd) {
		requirePositive("degree", outbound);
		Topology t = new Topology(n);
		int k = Math.min(outbound, n - 1);
		int[] candidates = new int[n - 1];
		for (int i = 1; i <= n; i++) {
			int c = 0;
			for (int j = 1; j <= n; j++) {
				if (j != i) candidates[c++] = j;
			}
			// Partial Fisher-Yates: the first k entries become a uniform sample without replacement.
			for (int s = 0; s < k; s++) {
				int pick = s + rnd.nextInt(candidates.length - s);
				int tmp = candidates[s];
				candidates[s] = candidates[pick];
				candidates[pick] = tmp;
				t.addLink(i, candidates[s]);
			}
		}
		return t;
	}

	/**
	 * A uniformly-ish random {@code degree}-regular graph (every node has exactly {@code degree}
	 * peers), built by pairing connection stubs while avoiding self-loops and duplicate links,
	 * restarting when stuck (Steger and Wormald, 1999).
	 */
	public static Topology randomRegular(int n, int degree, Random rnd) {
		requirePositive("degree", degree);
		if (degree >= n) {
			throw new IllegalArgumentException("random-regular needs degree < number of nodes (" + degree + " >= " + n + ")");
		}
		if ((n * degree) % 2 != 0) {
			throw new IllegalArgumentException("random-regular needs nodes x degree to be even (" + n + " x " + degree + ")");
		}
		for (int attempt = 0; attempt < 1000; attempt++) {
			Topology t = tryPairing(n, degree, rnd);
			if (t != null) return t;
		}
		throw new IllegalStateException("Could not build a " + degree + "-regular graph on " + n + " nodes");
	}

	private static Topology tryPairing(int n, int degree, Random rnd) {
		Topology t = new Topology(n);
		List<Integer> stubs = new ArrayList<>(n * degree);
		for (int i = 1; i <= n; i++) {
			for (int d = 0; d < degree; d++) stubs.add(i);
		}
		while (!stubs.isEmpty()) {
			boolean paired = false;
			for (int tries = 0; tries < 50 && !paired; tries++) {
				int x = rnd.nextInt(stubs.size());
				int y = rnd.nextInt(stubs.size());
				int u = stubs.get(x), v = stubs.get(y);
				if (x != y && u != v && !t.hasLink(u, v)) {
					t.addLink(u, v);
					removeStubs(stubs, x, y);
					paired = true;
				}
			}
			if (!paired && !pairAnySuitable(t, stubs)) {
				return null;
			}
		}
		return t;
	}

	/** Scans for any pairable stubs when random tries keep failing; false means stuck. */
	private static boolean pairAnySuitable(Topology t, List<Integer> stubs) {
		for (int x = 0; x < stubs.size(); x++) {
			for (int y = x + 1; y < stubs.size(); y++) {
				int u = stubs.get(x), v = stubs.get(y);
				if (u != v && !t.hasLink(u, v)) {
					t.addLink(u, v);
					removeStubs(stubs, x, y);
					return true;
				}
			}
		}
		return false;
	}

	private static void removeStubs(List<Integer> stubs, int x, int y) {
		stubs.remove(Math.max(x, y));
		stubs.remove(Math.min(x, y));
	}

	/** Each pair linked independently with probability {@code p} (G(n, p)). */
	public static Topology erdosRenyi(int n, double p, Random rnd) {
		if (p < 0 || p > 1) {
			throw new IllegalArgumentException("Edge probability must be in [0, 1], got " + p);
		}
		Topology t = new Topology(n);
		for (int i = 1; i <= n; i++) {
			for (int j = i + 1; j <= n; j++) {
				if (rnd.nextDouble() < p) t.addLink(i, j);
			}
		}
		return t;
	}

	/**
	 * Watts-Strogatz small world: a ring where each node links to its {@code degree/2} nearest
	 * neighbours on each side, then each link's far end is rewired to a random node with
	 * probability {@code rewire}.
	 */
	public static Topology smallWorld(int n, int degree, double rewire, Random rnd) {
		requirePositive("degree", degree);
		if (degree % 2 != 0 || degree >= n) {
			throw new IllegalArgumentException("small-world needs an even degree below the number of nodes, got " + degree);
		}
		if (rewire < 0 || rewire > 1) {
			throw new IllegalArgumentException("Rewire probability must be in [0, 1], got " + rewire);
		}
		Topology t = new Topology(n);
		for (int i = 1; i <= n; i++) {
			for (int j = 1; j <= degree / 2; j++) {
				t.addLink(i, ((i - 1 + j) % n) + 1);
			}
		}
		for (int j = 1; j <= degree / 2; j++) {
			for (int i = 1; i <= n; i++) {
				int far = ((i - 1 + j) % n) + 1;
				if (rnd.nextDouble() < rewire && t.degree(i) < n - 1) {
					int target;
					do {
						target = 1 + rnd.nextInt(n);
					} while (target == i || t.hasLink(i, target));
					t.links.remove(key(i, far));
					t.addLink(i, target);
				}
			}
		}
		return t;
	}

	/**
	 * Barabasi-Albert preferential attachment: start from {@code m + 1} fully linked nodes, then
	 * each further node links to {@code m} distinct existing nodes chosen with probability
	 * proportional to their degree. Produces a few highly connected hubs.
	 */
	public static Topology scaleFree(int n, int m, Random rnd) {
		requirePositive("degree", m);
		Topology t = new Topology(n);
		int seed = Math.min(n, m + 1);
		List<Integer> endpoints = new ArrayList<>(); // each node appears once per link end
		for (int i = 1; i <= seed; i++) {
			for (int j = i + 1; j <= seed; j++) {
				t.addLink(i, j);
				endpoints.add(i);
				endpoints.add(j);
			}
		}
		for (int v = seed + 1; v <= n; v++) {
			List<Integer> chosen = new ArrayList<>();
			while (chosen.size() < Math.min(m, v - 1)) {
				int u = endpoints.isEmpty() ? 1 + rnd.nextInt(v - 1) : endpoints.get(rnd.nextInt(endpoints.size()));
				if (!chosen.contains(u)) chosen.add(u);
			}
			for (int u : chosen) {
				t.addLink(v, u);
				endpoints.add(v);
				endpoints.add(u);
			}
		}
		return t;
	}

	/**
	 * Reads links from a CSV file with lines {@code from,to[,throughput_bps[,latency_ms]]}. A first
	 * line that does not start with a number is treated as a header. Missing throughput or latency
	 * values are sampled like generated links.
	 */
	public static Topology fromFile(String path, int n) throws IOException {
		Topology t = new Topology(n);
		try (BufferedReader br = new BufferedReader(new FileReader(path))) {
			String line;
			int lineNo = 0;
			while ((line = br.readLine()) != null) {
				lineNo++;
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) continue;
				String[] v = line.split(",");
				if (lineNo == 1 && !v[0].trim().matches("-?\\d+")) continue; // header
				if (v.length < 2) {
					throw new IOException(path + ":" + lineNo + ": expected from,to[,throughput[,latency]]");
				}
				try {
					int a = Integer.parseInt(v[0].trim());
					int b = Integer.parseInt(v[1].trim());
					float bps = v.length > 2 && !v[2].isBlank() ? Float.parseFloat(v[2].trim()) : Float.NaN;
					float lat = v.length > 3 && !v[3].isBlank() ? Float.parseFloat(v[3].trim()) : Float.NaN;
					if (a < 1 || a > n || b < 1 || b > n) {
						throw new IOException(path + ":" + lineNo + ": node ID outside 1.." + n);
					}
					if (bps <= 0 || lat < 0) {
						throw new IOException(path + ":" + lineNo + ": throughput must be > 0 and latency >= 0");
					}
					t.addLink(a, b, bps, lat);
				} catch (NumberFormatException e) {
					throw new IOException(path + ":" + lineNo + ": " + e.getMessage(), e);
				}
			}
		}
		return t;
	}

	private static void requirePositive(String what, int value) {
		if (value < 1) {
			throw new IllegalArgumentException("Topology " + what + " must be at least 1, got " + value);
		}
	}
}
