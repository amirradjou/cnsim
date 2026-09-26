package ca.yorku.cmg.cnsim.engine;

import java.util.Random;

public abstract class AbstractNetworkSampler implements ISowable {
	
	protected Sampler sampler;
	protected Random random = new Random();
	protected long randomSeed;
	
    protected float netThroughputMean;
    protected float netThroughputSD;    
    /** One-way link latency (ms); both 0 means the network has no latency. */
    protected float netLatencyMean;
    protected float netLatencySD;

    
    public AbstractNetworkSampler() {
    	LoadConfig();
    }
    
     
    /**
     * Constructs a new AbstractSampler object with the given parameters. Parameters define means and standard deviations of the distribution to be used.
     *
     * @param netThroughputMean     The mean network throughput (bps).
     * @param netThroughputSD       The standard deviation of network throughput (bps).
     * @throws ArithmeticException if any of the provided values are less than 0.
     */
    public AbstractNetworkSampler(float netThroughputMean, 
    		float netThroughputSD,
    		Sampler sampler) {

        if(netThroughputMean < 0)
    		throw new ArithmeticException("Network Throughput Mean < 0");
        this.netThroughputMean = netThroughputMean;
        if(netThroughputSD < 0)
    		throw new ArithmeticException("Network Throughput Standard Deviation < 0");
        this.netThroughputSD = netThroughputSD;

    }
    
	/**
	 * Returns the mean throughput of an arbitrary node to node connection in bits per second (bps)
	 * @return The mean throughput of an arbitrary node to node connection in bits per second (bps)
	 */
	public float getNetThroughputMean() {
        return netThroughputMean;
    }

	/**
	 * Sets the mean throughput of an arbitrary node to node connection in bits per second (bps)
	 * @param netThroughputMean The mean throughput of an arbitrary node to node connection in bits per second (bps)
	 */
	public void setNetThroughputMean(float netThroughputMean) {
    	if(netThroughputMean < 0)
    		throw new ArithmeticException("Network Throughput Mean < 0");
        this.netThroughputMean = netThroughputMean;
    }

	/**
	 * Returns the standard deviation throughput of an arbitrary node to node connection in bits per second (bps)
	 * @return The standard deviation throughput of an arbitrary node to node connection in bits per second (bps)
	 */
	public float getNetThroughputSD() {
        return netThroughputSD;
    }

	/**
	 * Sets the standard deviation throughput of an arbitrary node to node connection in bits per second (bps)
	 * @param netThroughputSD The standard deviation throughput of an arbitrary node to node connection in bits per second (bps)
	 */
	public void setNetThroughputSD(float netThroughputSD) {
    	if(netThroughputSD < 0)
    		throw new ArithmeticException("Network Throughput Standard Deviation < 0");
        this.netThroughputSD = netThroughputSD;
    }

    

	
	public Sampler getSampler() {
		return sampler;
	}

	public void setSampler(Sampler sampler) {
		this.sampler = sampler;
	}

	public Random getRandom() {
		return random;
	}

	public void setRandom(Random random) {
		this.random = random;
	}
	
    @Override
	public void setSeed(long seed) {
    	randomSeed = seed;
    	random.setSeed(seed);
    }
    
    
	/**
	 * Returns a sample of a throughput based on set mean and SD  
	 * @return The throughput value in bits per second (bps)
	 */
    public abstract float getNextConnectionThroughput();


    public void LoadConfig() {
        this.setNetThroughputMean(Config.getPropertyFloat("net.throughputMean"));
        this.setNetThroughputSD(Config.getPropertyFloat("net.throughputSD"));
        this.setNetLatency(Config.getPropertyFloat("net.latencyMean", 0f), Config.getPropertyFloat("net.latencySD", 0f));
    }

    /**
     * Sets the latency distribution of links (truncated normal, milliseconds).
     * @param mean Mean one-way latency in ms, at least 0.
     * @param sd Standard deviation in ms, at least 0.
     */
    public void setNetLatency(float mean, float sd) {
        if (mean < 0 || sd < 0)
            throw new ArithmeticException("Network latency mean and SD must be >= 0");
        this.netLatencyMean = mean;
        this.netLatencySD = sd;
    }

    /**
     * @return Whether links have latency (net.latencyMean or net.latencySD above 0).
     */
    public boolean hasLatency() {
        return netLatencyMean > 0 || netLatencySD > 0;
    }

    /**
     * Returns a sample of one-way link latency. Does not touch the random stream when the
     * network has no latency.
     * @return Latency in milliseconds (0 when not configured).
     */
    public float getNextConnectionLatency() {
        if (!hasLatency()) {
            return 0;
        }
        return sampler.getGaussian(netLatencyMean, netLatencySD, random);
    }
    
}
