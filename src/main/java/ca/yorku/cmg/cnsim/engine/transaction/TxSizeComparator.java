package ca.yorku.cmg.cnsim.engine.transaction;

import java.util.Comparator;

/**
 * Orders transactions by size, smallest first; equal sizes compare as equal.
 */
public class TxSizeComparator implements Comparator<Transaction>{
	@Override
	public int compare(Transaction t1, Transaction t2) {
		return Float.compare(t1.getSize(), t2.getSize());
	}
}
