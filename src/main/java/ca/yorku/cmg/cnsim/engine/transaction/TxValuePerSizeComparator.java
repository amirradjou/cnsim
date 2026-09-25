package ca.yorku.cmg.cnsim.engine.transaction;

import java.util.Comparator;

/**
 * Orders transactions by fee per byte, highest first. Transactions with the same fee rate
 * compare as equal, so a stable sort keeps them in pool (arrival) order.
 * <p>
 * Earlier versions returned "greater" for both orders of an equal pair, which breaks the
 * {@link Comparator} contract and made the order of such pairs depend on the sorting algorithm.
 */
public class TxValuePerSizeComparator implements Comparator<Transaction> {
	@Override
	public int compare(Transaction t1, Transaction t2) {
		return Float.compare(t2.getValue() / t2.getSize(), t1.getValue() / t1.getSize());
	}
}
