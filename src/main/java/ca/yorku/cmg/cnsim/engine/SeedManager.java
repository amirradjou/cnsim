package ca.yorku.cmg.cnsim.engine;

import java.util.Arrays;
import java.util.Random;

public class SeedManager {
	private long[] seedArray = null;
	private boolean[] seedUpdateFlags = null;
	private int currentIndex = 0;
	private int simID = 0;
	
	public int getSimD() {
		return simID;
	}

	public void setSimD(int simD) {
		this.simID = simD;
	}

	public SeedManager () {
	}

	/**
	 * The seed of simulation simID's own random stream, derived from a configured base seed: the
	 * simID-th output of SplitMix64 started at base. The seeds used to be base + simID, but
	 * java.util.Random's first output barely moves with its seed (for seeds 223 to 622 the first
	 * nextDouble() lies between 0.675 and 0.767), so every simulation started with nearly the same
	 * draw. Scrambled seeds make the simulations' streams independent from the first draw on.
	 */
	public static final String DERIVATION_KEY = "sim.seedDerivation";

	/**
	 * The per-simulation seed for this run: {@link #perSimulation} by default, or base + simID
	 * with {@code sim.seedDerivation = legacy}, which the thesis configs set so that they keep
	 * reproducing the thesis runs.
	 */
	public static long derive(long base, int simID) {
		String mode = Config.hasProperty(DERIVATION_KEY) ? Config.getPropertyString(DERIVATION_KEY).trim() : "scrambled";
		switch (mode) {
			case "scrambled":
				return perSimulation(base, simID);
			case "legacy":
				return base + simID;
			default:
				throw new ConfigException(DERIVATION_KEY + " must be scrambled or legacy, but was '" + mode + "'");
		}
	}

	public static long perSimulation(long base, int simID) {
		long z = base + simID * 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
	
	public SeedManager (long[] seedChain, boolean[] flagArray, int sID) {
		super();
		seedArray = seedChain;
		seedUpdateFlags = flagArray;
		simID = sID;
	}
	
	public String getSeedUpdateFlag() {
		return Arrays.toString(seedUpdateFlags);
	}

	public void setSeedUpdateFlag(boolean[] seedUpdateFlag) {
		this.seedUpdateFlags = seedUpdateFlag;
	}

	protected void setSeedArray(long[] seedChain) {
		seedArray = seedChain;		
	}
	
	public String getSeedArray() {
		return(Arrays.toString(seedArray));
	}
		
	protected long nextSeed() {
		
		//Debug.p("     UPDATING seed:");
		//Debug.p("       Current Index: " + currentIndex);
		//Debug.p("       Next seed in line: " + seedArray[currentIndex]);
		//Debug.p("       Seed to be updated?: " + seedUpdateFlags[currentIndex]);
		//Debug.p("       Seed to be updated?: " +  (seedArray[currentIndex] + (seedUpdateFlags[currentIndex] ? simID : 0)));

		long newSeed = seedUpdateFlags[currentIndex] ? derive(seedArray[currentIndex], simID) : seedArray[currentIndex];
		currentIndex = (currentIndex + 1) % seedArray.length;
		return newSeed;
	}
	
	
	public void updateSeed(Random random) {
		long randomSeed = nextSeed();
    	random.setSeed(randomSeed);
      	//Debug.p("Setting the seed of " + random.toString() + " to " + randomSeed);
	}
}
