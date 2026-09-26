package ca.yorku.cmg.cnsim.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.yorku.cmg.cnsim.engine.event.Event;
import ca.yorku.cmg.cnsim.engine.node.NodeStub;
import ca.yorku.cmg.cnsim.engine.transaction.TransactionGroup;

/** Redrawing a pending mining event when the random stream switches seed. */
class RedrawPendingValidationTest {

	private Properties savedProps;
	private boolean savedInitialized;
	private Simulation sim;
	private NodeStub node;

	@BeforeEach
	void setUp() {
		savedProps = new Properties();
		savedProps.putAll(Config.prop);
		savedInitialized = Config.initialized;
		Config.init("src/test/resources/application.properties");
		sim = new Simulation(1);
		Sampler sampler = new Sampler();
		sampler.setNodeSampler(new StandardNodeSampler(sampler, new long[] {7}, new boolean[] {false}, 1));
		sim.setSampler(sampler);
		node = new NodeStub(sim);
		node.setHashPower(1e5f);
	}

	@AfterEach
	void restore() {
		Config.prop.clear();
		Config.prop.putAll(savedProps);
		Config.initialized = savedInitialized;
	}

	@Test
	void replacesThePendingEventWithOneDrawnFromNow() {
		node.startMining(node.scheduleValidationEvent(new TransactionGroup(), 0));
		Event before = node.getNextValidationEvent();

		node.redrawPendingValidation(500);

		assertTrue(before.ignoreEvt(), "the old event is cancelled");
		Event after = node.getNextValidationEvent();
		assertNotSame(before, after);
		assertFalse(after.ignoreEvt());
		assertTrue(after.getTime() >= 500);
		assertTrue(node.isMining());
	}

	@Test
	void doesNothingWhenTheNodeIsNotMining() {
		node.redrawPendingValidation(500);
		assertNull(node.getNextValidationEvent());
		assertTrue(sim.getQueue().isEmpty());
	}
}
