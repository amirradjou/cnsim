package ca.yorku.cmg.cnsim.engine.network;

import ca.yorku.cmg.cnsim.engine.Config;
import ca.yorku.cmg.cnsim.engine.Simulation;
import ca.yorku.cmg.cnsim.engine.node.NodeSet;
import ca.yorku.cmg.cnsim.engine.reporter.Reporter;

/**
 * Generic network structure.
 * 
 * @author Sotirios Liaskos for the Conceptual Modeling Group @ York University
 * 
 */
public abstract class AbstractNetwork {
	
	protected NodeSet ns;

	public float[][] Net;

	/**
	 * One-way latency in milliseconds between node pairs, added to the transmission time, or
	 * {@code null} when the network has no latency (the original model).
	 */
	protected float[][] latency;

	/**
	 * Constructor. 
	 * @param ns A NodeSet object representing the nodes of the network.
	 * @throws Exception 
	 */
	public AbstractNetwork(NodeSet ns) throws Exception {
        int maxNodes = Config.getPropertyInt("sim.maxNodes");
		if (maxNodes < ns.getNodeSetCount()) {
			throw new Exception("Node count exceed maximum allowed number of nodes.");
		}
		Net = new float [maxNodes + 1][maxNodes + 1];
        this.ns = ns;
	}
	
	
	/**
	 * Constructor. Create an empty object. For testing purposes only. 
	 */
	public AbstractNetwork() {
	}
	
	
	/**
	 * @return The NodeSet based on which the network is constructed. 
	 * @author Sotirios Liaskos
	 */
	public NodeSet getNodeSet() {
		return ns;
	}


	/**
	 * Returns the propagation time of a message of size Size from Origin to Destination
	 * @param origin The ID of the origin node.
	 * @param destination The ID of the destination node.
	 * @param size The size of the message in bytes
	 * @return The propagation time *in milliseconds* or -1 if the nodes are not connected.
	 */
	public long getPropagationTime(int origin, int destination, float size) {
		if(size < 0)
			throw new ArithmeticException("Size < 0");
		float bps = getThroughput(origin, destination);
		long transmission = getPropagationTime(bps, size);
		if (latency == null || transmission < 0) {
			return transmission;
		}
		return transmission + Math.round(latency[origin][destination]);
	}

	/**
	 * Sets the one-way latency from origin to destination. Setting a zero latency on a network
	 * without latencies leaves it without them.
	 * @param origin The ID of the origin node.
	 * @param destination The ID of the destination node.
	 * @param ms Latency in milliseconds, at least 0.
	 */
	public void setLatency(int origin, int destination, float ms) {
		if (ms < 0 || Float.isNaN(ms))
			throw new ArithmeticException("Latency must be >= 0, got " + ms);
		if (latency == null) {
			if (ms == 0) return;
			latency = new float[Net.length][Net.length];
		}
		latency[origin][destination] = ms;
	}

	/**
	 * @return The one-way latency (ms) from origin to destination, 0 if the network has none.
	 */
	public float getLatency(int origin, int destination) {
		return (latency == null) ? 0 : latency[origin][destination];
	}

	
	/**
	 * Returns the propagation time of a message of size `size` in a channel of throughput `throughput`
	 * @param throughput in Bits per Second (bps)
	 * @param size The size of the message in bytes
	 * @return The propagation time *in milliseconds* or -1 if the nodes are not connected.
	 */
	public long getPropagationTime(float throughput, float size) {
		if(size < 0)
			throw new ArithmeticException("Size < 0");
		if(throughput < 0)
			throw new ArithmeticException("Throughput < 0");

	    if(throughput == 0)
	        return (-1);
	    else
		    // Multiply by 8 because Size is in terms of bytes but throughput is in terms of bits.
			// Multiply by 1000 because throughput is measured in bits/second but 
			// expected output is in terms of milliseconds.
	    	return(Math.round((size * 8 * 1000)/throughput));
	}
	
	
	
	/**
	 * Returns the throughput between Origin and Destination.
	 * @param Origin The ID of the origin node.
	 * @param Destination The ID of the destination node.
	 * @return The throughput of the connection in bits per second (bps)
	 */
	public float getThroughput(int Origin, int Destination) {
		if(Origin < 0)
			throw new ArithmeticException("Origin < 0");
		if(Destination < 0)
			throw new ArithmeticException("Destination < 0");
		return Net[Origin][Destination];
	}

	public void setThroughput(int origin, int destination, float throughput) {
		Reporter.addNetEvent(Simulation.currentSimulationID, origin, destination, throughput, Simulation.currTime);
		if(origin < 0)
			throw new ArithmeticException("Origin < 0");
		if(destination < 0)
			throw new ArithmeticException("Destination < 0");
		if(throughput < 0)
			throw new ArithmeticException("Throughput < 0");
		Net[origin][destination] = throughput;
	}

	
	
	
	/**
	 * Calculates the average throughput a given origin node has with the rest of the network.
	 * Based on averaging the throughput of the origin with all other nodes.
	 *
	 * @param origin The origin node for which to calculate the average throughput.
	 * @return The average throughput for the origin node.
	 */
	public float getAvgTroughput(int origin) {
		float sum=0;
		int i=1, count = 0;
		
		for (i=1; i <= Config.getPropertyInt("net.numOfNodes"); i++) {
	        if(i!=origin)
	        {
	            sum += (Net[origin][i] + Net[i][origin]);
	            count += 2;
	        }
	    }
	    return (sum/count);
	}
	
	/**
	 * Prints the network matrix.
	 * Each element of the matrix represents the throughput between two nodes.
	 */
	public void printNetwork() {
		for (float[] x : Net) {
		   for (float y : x) {
		        System.out.printf("%3.1f ", y);
		   }
		   System.out.println();
		}
	}
	
	
	/**
	 * TODO: Alternate implementation of network printing. Pick the one that is better. 
	 */
	public void printNetwork2() {
		for (int i = 0; i < Net.length; i++) {
			for (int j = 0; j < Net[i].length; j++) {
				System.out.print(Net[i][j] + " ");
			}
			System.out.println();
		}
	}
	
}