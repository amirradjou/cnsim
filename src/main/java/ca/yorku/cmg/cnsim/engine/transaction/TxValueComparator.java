package ca.yorku.cmg.cnsim.engine.transaction;

import java.util.Comparator;

/**
 * Orders transactions by value, lowest first; equal values compare as equal.
 */
public class TxValueComparator implements Comparator<Transaction>{
	@Override
	public int compare(Transaction t1, Transaction t2) {
		return Float.compare(t1.getValue(), t2.getValue());
	}
}
