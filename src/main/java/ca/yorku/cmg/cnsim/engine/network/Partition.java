package ca.yorku.cmg.cnsim.engine.network;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import ca.yorku.cmg.cnsim.engine.Config;

/**
 * A network partition: between {@code start} (inclusive) and {@code end} (exclusive) the nodes of
 * one side cannot reach the others. A block or transaction sent across the cut while the partition
 * holds arrives when it heals, after its usual delay: the two sides reconnect and exchange what
 * they missed. Messages within a side, messages sent before the partition starts (already on the
 * wire) and messages sent after it ends are not affected.
 * <p>
 * Configured by {@code net.partition.nodes} (the IDs of the nodes on one side, e.g.
 * {@code {1,2,3}}), {@code net.partition.start} and {@code net.partition.end} (ms). On a
 * peer-to-peer overlay, messages within a side still take the fastest path over the whole
 * overlay, which may pass through the other side; the cut only delays messages between sides.
 */
public final class Partition {

	public static final String NODES_KEY = "net.partition.nodes";
	public static final String START_KEY = "net.partition.start";
	public static final String END_KEY = "net.partition.end";

	private final Set<Integer> side;
	private final long start;
	private final long end;

	/**
	 * @param side IDs of the nodes on one side of the cut; every other node is on the other side.
	 * @param start Time (ms) the partition starts, at least 0.
	 * @param end Time (ms) the partition heals, after start.
	 */
	public Partition(Collection<Integer> side, long start, long end) {
		if (side.isEmpty()) {
			throw new IllegalArgumentException(NODES_KEY + " lists no nodes");
		}
		if (start < 0 || end <= start) {
			throw new IllegalArgumentException("a partition needs 0 <= " + START_KEY + " < " + END_KEY + ", but they are "
					+ start + " and " + end);
		}
		this.side = Collections.unmodifiableSet(new TreeSet<>(side));
		this.start = start;
		this.end = end;
	}

	/**
	 * The partition the configuration describes, or {@code null} if it describes none.
	 * @param nodeIDs IDs of all nodes of the simulation; the listed side must be a proper subset.
	 */
	public static Partition fromConfig(Collection<Integer> nodeIDs) {
		if (!Config.hasProperty(NODES_KEY)) {
			if (Config.hasProperty(START_KEY) || Config.hasProperty(END_KEY)) {
				throw new IllegalArgumentException(START_KEY + " and " + END_KEY + " need " + NODES_KEY
						+ ", the nodes on one side of the partition");
			}
			return null;
		}
		if (!Config.hasProperty(START_KEY) || !Config.hasProperty(END_KEY)) {
			throw new IllegalArgumentException(NODES_KEY + " needs " + START_KEY + " and " + END_KEY + " (ms)");
		}
		Set<Integer> side = new TreeSet<>();
		for (int id : Config.parseStringToIntArray(Config.getPropertyString(NODES_KEY).trim())) {
			if (!nodeIDs.contains(id)) {
				throw new IllegalArgumentException(NODES_KEY + " lists node " + id + ", but the nodes are " + new TreeSet<>(nodeIDs));
			}
			side.add(id);
		}
		if (side.size() == nodeIDs.size()) {
			throw new IllegalArgumentException(NODES_KEY + " lists every node; it should list the nodes on one side only");
		}
		return new Partition(side, Config.getPropertyLong(START_KEY), Config.getPropertyLong(END_KEY));
	}

	/** Whether a and b are on different sides. */
	public boolean separates(int a, int b) {
		return side.contains(a) != side.contains(b);
	}

	/** Whether the partition holds at time t. */
	public boolean holdsAt(long t) {
		return start <= t && t < end;
	}

	/**
	 * When a message from origin to destination, sent at sendTime with the given network and
	 * processing delay, arrives.
	 */
	public long arrivalTime(int origin, int destination, long sendTime, long delay) {
		if (holdsAt(sendTime) && separates(origin, destination)) {
			return end + delay;
		}
		return sendTime + delay;
	}

	public Set<Integer> getSide() {
		return side;
	}

	public long getStart() {
		return start;
	}

	public long getEnd() {
		return end;
	}
}
