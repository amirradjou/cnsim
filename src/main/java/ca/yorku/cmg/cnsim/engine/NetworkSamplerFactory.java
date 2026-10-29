package ca.yorku.cmg.cnsim.engine;

public class NetworkSamplerFactory {
	public AbstractNetworkSampler getNetworkSampler(Long seed, boolean seedFlag, Sampler outerSampler, Simulation sim) {
		AbstractNetworkSampler netSampler;
		netSampler = new StandardNetworkSampler(outerSampler);
		if (seed != null) {
			netSampler.setSeed(seedFlag ? SeedManager.derive(seed, sim.getSimID()) : seed);
		}
		return(netSampler);
    }
}
