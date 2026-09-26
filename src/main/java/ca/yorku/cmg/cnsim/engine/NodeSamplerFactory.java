package ca.yorku.cmg.cnsim.engine;

import ca.yorku.cmg.cnsim.engine.event.Event_SeedUpdate;

public class NodeSamplerFactory {
	
	
	public AbstractNodeSampler getSampler(
			String path,
			String seedChain,
			String changeTimes,
			String updateFlags,
			Sampler sampler,
			Simulation sim
			) throws Exception {
		
        //Check requirements
        
		boolean hasPath = (path != null);
	    	
		
        boolean hasNodeSeeds = false;
        long seeds[] = null;
        boolean flags[] = null;
        if ((seedChain != null && !seedChain.isEmpty())) {
        	seeds = Config.parseStringToArray(seedChain);
        	hasNodeSeeds = true;
        }
        
        boolean hasSwitchTimes = false;  
        long switchTimes[] = null;
        if ((seedChain != null && !seedChain.isEmpty())) {
        	hasSwitchTimes = true;
        	switchTimes = Config.parseStringToArray(changeTimes);
        	flags = Config.parseStringToBoolean(updateFlags);
        }


        //TODO: Validation code
    	
    	AbstractNodeSampler nodeSampler;
        
        if (hasPath) {
        	if (hasNodeSeeds) {
        		nodeSampler = new FileBasedNodeSampler(path, new StandardNodeSampler(sampler,seeds,flags,sim.getSimID()));
        	} else {
        		nodeSampler = new FileBasedNodeSampler(path, new StandardNodeSampler(sampler));
        	}
        } else {
        	if (hasNodeSeeds) {
        		nodeSampler = new StandardNodeSampler(sampler,seeds,flags,sim.getSimID());
        	} else {
        		nodeSampler = new StandardNodeSampler(sampler);
        	}
        }
        
        if (sim.getSimID() == 1) {
        	warnIfReplicasShareRandomness(seeds, flags, switchTimes);
        }

        //Schedule the switchover events
    	if (hasSwitchTimes) {
    		if (!hasNodeSeeds) {
    			throw new Exception("Error in NodeSamplerFactory: seed switch times given (" + Config.getPropertyString("node.sampler.seedUpdateTimes") +  ") but not seeds to switch around.");
    		} else {
    	        //Schedule seed change events
    	        for (int i = 0; i < switchTimes.length; i++) {
    	        	Debug.p("    Scheduling sampler with chain [...] to swich to next seed at " + switchTimes[i]);
    	            sim.schedule(new Event_SeedUpdate(nodeSampler, switchTimes[i]));
    	        }
    		}
    	}
    	
    	return(nodeSampler);
	}

	/**
	 * Mining intervals are drawn from the node sampler's random stream. Simulations are
	 * independent replicas only from the moment that stream uses a seed with its update flag set
	 * (seed + simulation ID); intervals drawn earlier are the same in every simulation, and a
	 * pending mining event keeps its time until the node finds a block. Returns a warning when that
	 * moment is after t = 0 or never comes before the run ends, null otherwise.
	 */
	static String replicaIndependenceWarning(long[] seeds, boolean[] flags, long[] switchTimes, long terminateAt) {
		if (seeds == null || seeds.length == 0 || flags == null || flags.length == 0) {
			return null;
		}
		long independentFrom = -1;
		if (flags[0]) {
			independentFrom = 0;
		} else if (switchTimes != null) {
			// Switch i moves to seeds[i + 1] (cyclically).
			for (int i = 0; i < switchTimes.length; i++) {
				int next = (i + 1) % seeds.length;
				if (next < flags.length && flags[next]) {
					independentFrom = switchTimes[i];
					break;
				}
			}
		}
		if (independentFrom == 0) {
			return null;
		}
		if (independentFrom < 0 || independentFrom >= terminateAt) {
			return "Warning: the node sampler never switches to a per-simulation seed before sim.terminate.atTime, "
					+ "so every simulation uses the same mining randomness and the replicas are not independent. "
					+ "Set node.sampler.seedUpdateTimes = {0} (with updateSeedFlags {false,true}) to make them independent.";
		}
		return "Warning: the node sampler switches to a per-simulation seed only at t = " + independentFrom
				+ " ms; mining intervals drawn before then are shared by all simulations, which correlates the "
				+ "replicas. node.sampler.seedUpdateTimes = {0} makes them independent from the start.";
	}

	private static void warnIfReplicasShareRandomness(long[] seeds, boolean[] flags, long[] switchTimes) {
		if (!Config.hasProperty("sim.numSimulations") || Config.getPropertyInt("sim.numSimulations") < 2
				|| !Config.hasProperty("sim.terminate.atTime")) {
			return;
		}
		String warning = replicaIndependenceWarning(seeds, flags, switchTimes, Config.getPropertyLong("sim.terminate.atTime"));
		if (warning != null) {
			System.out.println("    " + warning);
		}
	}
}
