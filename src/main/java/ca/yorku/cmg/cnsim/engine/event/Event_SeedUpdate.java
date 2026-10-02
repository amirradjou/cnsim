package ca.yorku.cmg.cnsim.engine.event;

import ca.yorku.cmg.cnsim.engine.AbstractNodeSampler;
import ca.yorku.cmg.cnsim.engine.IMultiSowable;
import ca.yorku.cmg.cnsim.engine.Simulation;
import ca.yorku.cmg.cnsim.engine.node.INode;
import ca.yorku.cmg.cnsim.engine.node.Node;
import ca.yorku.cmg.cnsim.engine.reporter.Reporter;


public class Event_SeedUpdate extends Event {
	IMultiSowable sampler;
	long randomSeed;
	
    
    public Event_SeedUpdate(IMultiSowable sampler, long time){
    	super();
    	this.sampler = sampler;
    	super.setTime(time);
    }
    

    /**
     * Switches the sampler to its next seed. For the node sampler, which draws the mining
     * intervals, every pending mining event is then redrawn from the new stream: otherwise events
     * drawn before the switch would keep their times and simulations meant to diverge at this
     * point would still share them (see {@link Node#redrawPendingValidation(long)}).
     *
     * @param sim The simulation instance.
     */
    @Override
    public void happen(Simulation sim) {
        super.happen(sim);
        sampler.updateSeed();
        if (sampler instanceof AbstractNodeSampler) {
            for (INode n : sim.getNodeSet().getNodes()) {
                if (n instanceof Node node) {
                    node.redrawPendingValidation(getTime());
                }
            }
        }
        Reporter.addEvent(
        		sim.getSimID(),
        		this.getEvtID(), 
        		this.getTime(), 
        		System.currentTimeMillis() - Simulation.sysStartTime, 
        		this.getClass().getSimpleName(), 
        		-1, 
        		-1);
    }
}
