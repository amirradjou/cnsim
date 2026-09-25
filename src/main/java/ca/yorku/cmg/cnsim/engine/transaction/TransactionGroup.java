package ca.yorku.cmg.cnsim.engine.transaction;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A list containing various transactions. Can be used as a block or other needed grouping (e.g. pool).
 * <p>
 * Performance note: when the group owns its list (it was created empty, loaded from a file or
 * returned by {@link #getTopN(float, Comparator)}) it keeps two derived structures, a sorted view
 * for {@link #getTopN(float, Comparator)} and a transaction-ID index for the {@code contains}
 * queries. Both are validated against the list's modification count, so they stay correct even
 * when callers mutate {@link #getTransactions()} directly; they only change how fast results are
 * computed, never what they are.
 *
 * @author Sotirios Liaskos for the Enterprise Systems Group @ York University
 */
public class TransactionGroup implements ITxContainer {

    /**
     * The list type a group creates for itself. It exposes {@link ArrayList}'s structural
     * modification count so the derived structures can tell whether the list changed.
     */
    static final class TxList extends ArrayList<Transaction> {
        private static final long serialVersionUID = 1L;

        TxList() {
            super();
        }

        int modifications() {
            return modCount;
        }
    }

    private List<Transaction> group;
    protected int groupID;
    protected float totalValue;
    protected float totalSize;

    // Sorted view of `group` used by getTopN. Valid while `group` is the same, unmodified TxList.
    private Comparator<Transaction> sortedBy;
    private List<Transaction> sortedSource;
    private ArrayList<Transaction> sorted;
    private int sortedAtModification;
    // True when `sortedBy` answers inconsistently for two neighbours in the view (each "sorts
    // after" the other). Their relative order then depends on the sorting algorithm's internals,
    // so getTopN falls back to sorting the whole group exactly as it always did.
    private boolean sortedInconsistent;

    // Transaction ID -> number of occurrences in `group`. Same validity rule as the sorted view.
    private List<Transaction> indexSource;
    private HashMap<Long, Integer> idIndex;
    private int indexedAtModification;

    ////////// Constructors //////////

    /**
     * Plain constructor, simply initializes the internal data structure.
     */
    public TransactionGroup() {
        group = new TxList();
    }

    /**
     * Accepts an already created ArrayList of transactions. Calculates the total value and size in the group.
     *
     * @param initial An already created ArrayList of transactions
     */
    public TransactionGroup(List<Transaction> initial) {
        group = initial;
        for (Transaction t : initial) {
            totalValue += t.getValue();
            totalSize += t.getSize();
        }
    }

    /**
     * Loads a transaction group from a text file. Each line in the file is a separate transaction.
     * Each transaction is a comma separated string containing the following information:
     * Transaction ID, Time Created, Total Value, Total Size, First Arrival NodeID.
     * Transaction ID must run from <tt>1</tt> to <tt>n</tt> strictly increasing by 1 at each step  (error otherwise). Time must not decrease as transactions IDs increase.
     * Time Created: a long integer representing the number of milliseconds (ms) from a fixed time 0.
     * Total Value: in user defined tokens depending on network.
     * TODO: Is this bytes?
     * Total Size: in bytes
     * First Arrival NodeID: the node at which the transaction first arrives
     *
     * @param fileName  A name to the text file containing the transactions.
     * @param hasHeader Whether the file has a header.
     * @throws IOException Error finding or reading the file.
     */
    public TransactionGroup(String fileName, boolean hasHeader) throws IOException {
        this();

        String l;
        String delimiter = ",";

        int tCount = 1;
        long lastTime = 0;
        int id;
        long time;
        float value;
        float size;
        int nodeID;

        BufferedReader br = new BufferedReader(new FileReader(fileName));
        while ((l = br.readLine()) != null) {
            if (hasHeader) {
                hasHeader = false;
            } else {
                String[] t = l.split(delimiter);
                id = Integer.parseInt(t[0]);
                if (id != tCount)
                    throw new IllegalArgumentException("Error in workload file: transaction IDs must start from 1 and strictly increase by 1.");
                tCount++;
                time = Long.parseLong(t[1]);
                if (time < lastTime)
                    throw new IllegalArgumentException("Error in workload file: time must not decrease as transaction IDs increase.");
                lastTime = time;
                value = Float.parseFloat(t[2]);
                size = Float.parseFloat(t[3]);
                nodeID = Integer.parseInt(t[4]);

                this.addTransaction(new Transaction(id, time, value, size, nodeID));
            }
        }
        br.close();
    }

    ////////// Modifiers //////////

    /**
     * Replace transaction group with a new one.
     *
     * @param initial An array list of <tt>Transaction</tt> objects, to replace the existing one.
     */
    public void updateTransactionGroup(List<Transaction> initial) {
        totalValue = 0;
        totalSize = 0;
        group = initial;
        for (Transaction t : initial) {
            totalValue += t.getValue();
            totalSize += t.getSize();
        }
    }

    /**
     * See {@linkplain ITxContainer#addTransaction(Transaction)}.
     */
    @Override
    public void addTransaction(Transaction t) {
        boolean keepSorted = sortedIsCurrent() && !sortedInconsistent;
        boolean keepIndex = indexIsCurrent();
        group.add(t);
        totalSize += t.getSize();
        totalValue += t.getValue();
        if (keepSorted) {
            insertIntoSorted(t);
        }
        if (keepIndex) {
            idIndex.merge(t.getID(), 1, Integer::sum);
            indexedAtModification = modifications();
        }
    }

    /**
     * See {@linkplain ITxContainer#removeTransaction(Transaction)}.
     */
    @Override
    public void removeTransaction(Transaction t) {
        int position = group.indexOf(t);
        if (position < 0) return;
        removeAt(position, t);
    }


    /**
     * Like removeTransaction(Transaction) but with ID as an argument.
     * See {@linkplain ITxContainer#removeTransaction(Transaction)}.
     * @param txID
     */
    public void removeTransaction(int txID) {
        Transaction t = getTransactionById((int) txID);
        int position = group.indexOf(t);
        if (position < 0) return;
        removeAt(position, t);
    }

    /**
     * Removes the element at {@code position}, which is {@code t}, and keeps the totals and the
     * derived structures in step.
     */
    private void removeAt(int position, Transaction t) {
        boolean keepSorted = sortedIsCurrent();
        boolean keepIndex = indexIsCurrent();
        group.remove(position);
        totalSize -= t.getSize();
        totalValue -= t.getValue();
        if (keepSorted) {
            sorted.remove(t);
            sortedAtModification = modifications();
        }
        if (keepIndex) {
            decrementIndex(t.getID(), 1);
            indexedAtModification = modifications();
        }
    }

    /**
     * Removes every occurrence of every transaction in {@code removeThese} (compared by identity,
     * as {@link List#removeAll(Collection)} does for {@link Transaction}) and updates the totals.
     * Linear in the size of both collections, where {@code removeAll} on a list is quadratic.
     *
     * @param removeThese The transactions to remove.
     */
    public void removeAllOf(Collection<Transaction> removeThese) {
        if (group.isEmpty() || removeThese.isEmpty()) return;
        Set<Transaction> doomed = Collections.newSetFromMap(new IdentityHashMap<>());
        doomed.addAll(removeThese);
        boolean keepSorted = sortedIsCurrent();
        boolean keepIndex = indexIsCurrent();
        int kept = 0;
        for (int read = 0; read < group.size(); read++) {
            Transaction t = group.get(read);
            if (doomed.contains(t)) {
                totalSize -= t.getSize();
                totalValue -= t.getValue();
                if (keepIndex) {
                    decrementIndex(t.getID(), 1);
                }
            } else {
                group.set(kept++, t);
            }
        }
        if (kept == group.size()) return;
        group.subList(kept, group.size()).clear();
        if (keepSorted) {
            sorted.removeIf(doomed::contains);
            sortedAtModification = modifications();
        }
        if (keepIndex) {
            indexedAtModification = modifications();
        }
    }

    /**
     * See {@linkplain ITxContainer#removeNextTx()}.
     */
    @Override
    public Transaction removeNextTx() {
        Transaction t = group.removeFirst();
        totalSize -= t.getSize();
        totalValue -= t.getValue();
        return t;
    }

    /**
     * See {@linkplain ITxContainer#extractGroup(TransactionGroup)}.
     * <p>
     * Equivalent to calling {@link #removeTransaction(Transaction)} for each transaction of
     * {@code g} in order (removing one occurrence per call and subtracting sizes and values in
     * that order), but linear rather than quadratic in the group sizes.
     */
    @Override
    public void extractGroup(TransactionGroup g) {
        List<Transaction> incoming = g.getTransactions();
        if (incoming.isEmpty() || group.isEmpty()) return;

        // How many copies of each (identical) transaction object the group holds.
        Map<Transaction, Integer> available = new IdentityHashMap<>();
        for (Transaction t : group) {
            available.merge(t, 1, Integer::sum);
        }
        // Decide what goes, subtracting totals in the same order as the one-by-one loop.
        Map<Transaction, Integer> toRemove = new IdentityHashMap<>();
        for (Transaction t : incoming) {
            Integer left = available.get(t);
            if (left == null || left == 0) continue;
            available.put(t, left - 1);
            toRemove.merge(t, 1, Integer::sum);
            totalSize -= t.getSize();
            totalValue -= t.getValue();
        }
        if (toRemove.isEmpty()) return;

        boolean keepSorted = sortedIsCurrent();
        boolean keepIndex = indexIsCurrent();
        // Removing one occurrence per call always takes the first remaining one, so the loop
        // removes the first k occurrences of each object; compact the list the same way.
        removeFirstOccurrences(group, new IdentityHashMap<>(toRemove));
        if (keepSorted) {
            removeFirstOccurrences(sorted, new IdentityHashMap<>(toRemove));
            sortedAtModification = modifications();
        }
        if (keepIndex) {
            for (Map.Entry<Transaction, Integer> e : toRemove.entrySet()) {
                decrementIndex(e.getKey().getID(), e.getValue());
            }
            indexedAtModification = modifications();
        }
    }

    /**
     * Removes, in place and preserving order, the first {@code counts.get(t)} occurrences of each
     * transaction {@code t} in {@code counts}. Consumes {@code counts}.
     */
    private static void removeFirstOccurrences(List<Transaction> list, Map<Transaction, Integer> counts) {
        int kept = 0;
        for (int read = 0; read < list.size(); read++) {
            Transaction t = list.get(read);
            Integer c = counts.get(t);
            if (c != null && c > 0) {
                counts.put(t, c - 1);
            } else {
                list.set(kept++, t);
            }
        }
        list.subList(kept, list.size()).clear();
    }

    ////////// Examine Content //////////

    /**
     * See {@linkplain ITxContainer#contains(Transaction)}.
     */
    @Override
    public boolean contains(Transaction t) {
        return contains(t.getID());
    }

    /**
     * See {@linkplain ITxContainer#contains(long)}.
     */
    @Override
    public boolean contains(long txID) {
        if (group instanceof TxList) {
            return currentIndex().containsKey(txID);
        }
        for (Transaction r : group) {
            if (r.getID() == txID) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the group overlaps with another transaction group, i.e.,
     * there is a transaction in {@code p} that also exists in the current group.
     *
     * @param p The <tt>TransactionGroup</tt> in question.
     * @return <tt>true</tt> of there is at least one transaction in <tt>p</tt> that is contained in the group, <tt>false</tt>, otherwise.
     */
    public boolean overlapsWithByObj(TransactionGroup p) {
        Set<Transaction> mine = Collections.newSetFromMap(new IdentityHashMap<>());
        mine.addAll(group);
        for (Transaction t : p.getTransactions()) {
            if (mine.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * As {@link TransactionGroup#overlapsWithByObj(TransactionGroup)} but criterion that is used is
     * transaction ID.
     *
     * @param g The {@link TransactionGroup} object in question.
     * @return <tt>true</tt> of there is at least one transaction in <tt>g</tt> that is contained in the group, <tt>false</tt>, otherwise.
     */
    public boolean overlapsWith(TransactionGroup g) {
        return g.containsAnyOf(idSet());
    }

    /**
     * @return The IDs of the transactions in the group.
     */
    public Set<Long> idSet() {
        Set<Long> ids = new HashSet<>(Math.max(16, group.size() * 2));
        for (Transaction t : group) {
            ids.add(t.getID());
        }
        return ids;
    }

    /**
     * @param ids A set of transaction IDs.
     * @return <tt>true</tt> if the group contains a transaction whose ID is in {@code ids}.
     */
    public boolean containsAnyOf(Set<Long> ids) {
        if (ids.isEmpty()) return false;
        for (Transaction t : group) {
            if (ids.contains(t.getID())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Retrieves a TransactionGroup containing the top N transactions based on
     * a given size limit and comparator.
     * <p>
     * Callers that invoke this repeatedly on a slowly changing group (a node's pool) should pass
     * the same comparator instance each time: the sorted view is then maintained incrementally
     * instead of being rebuilt on every call.
     *
     * @param sizeLimit The maximum cumulative size (in bytes) of transactions allowed in the result.
     * @param comp      The comparator used to sort the transactions.
     * @return A {@link TransactionGroup} object containing the top N transactions that do not
     * exceed sizeLimit based on given comparator.
     */
    public TransactionGroup getTopN(float sizeLimit, Comparator<Transaction> comp) {
        if (sizeLimit < 0) {
            throw new IllegalArgumentException(String.format("Size limit (%f) must be a positive integer", sizeLimit));
        }

        ArrayList<Transaction> result = new TxList();
        List<Transaction> sortedGroup = sortedView(comp);

        int i = 0;
        float sum = 0;
        while ((sum <= sizeLimit) && (i < sortedGroup.size())) {
            sum += sortedGroup.get(i).getSize();
            result.add(sortedGroup.get(i));
            i++;
        }
        if (sum > sizeLimit) { //Last one was exceeding the limit.
            result.remove(i - 1);
        }
        return (new TransactionGroup(result));
    }

    ////////// Derived structures //////////

    private int modifications() {
        return ((TxList) group).modifications();
    }

    private boolean sortedIsCurrent() {
        return sorted != null && group == sortedSource && modifications() == sortedAtModification;
    }

    private boolean indexIsCurrent() {
        return idIndex != null && group == indexSource && modifications() == indexedAtModification;
    }

    /**
     * Returns the group sorted by {@code comp}, identical to sorting the group from scratch. The
     * maintained view is used only while {@code comp} orders every pair of neighbours in it
     * consistently: the sort is then stable and its result is unique (ties keep list order, which
     * is where an appended transaction goes). Otherwise the group is sorted exactly as before.
     */
    private List<Transaction> sortedView(Comparator<Transaction> comp) {
        if (comp == sortedBy && sortedIsCurrent() && !sortedInconsistent) {
            return sorted;
        }
        List<Transaction> fresh = group.stream().sorted(comp).toList();
        if (group instanceof TxList) {
            sorted = new ArrayList<>(fresh);
            sortedBy = comp;
            sortedSource = group;
            sortedAtModification = modifications();
            sortedInconsistent = false;
            for (int i = 1; i < sorted.size() && !sortedInconsistent; i++) {
                sortedInconsistent = !consistentlyOrdered(sorted.get(i - 1), sorted.get(i));
            }
        }
        return fresh;
    }

    /** Inserts a newly added transaction into the (consistent) sorted view. */
    private void insertIntoSorted(Transaction t) {
        // Upper bound: after every element that t does not sort strictly before, as a stable sort would.
        int lo = 0;
        int hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sortedBy.compare(t, sorted.get(mid)) < 0) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        sorted.add(lo, t);
        if ((lo > 0 && !consistentlyOrdered(sorted.get(lo - 1), t))
                || (lo + 1 < sorted.size() && !consistentlyOrdered(t, sorted.get(lo + 1)))) {
            sortedInconsistent = true;
        }
        sortedAtModification = modifications();
    }

    /** True if {@code a} may precede {@code b} and the comparator agrees with itself about it. */
    private boolean consistentlyOrdered(Transaction a, Transaction b) {
        int ab = Integer.signum(sortedBy.compare(a, b));
        int ba = Integer.signum(sortedBy.compare(b, a));
        return ab <= 0 && ab == -ba;
    }

    private HashMap<Long, Integer> currentIndex() {
        if (!indexIsCurrent()) {
            idIndex = new HashMap<>(Math.max(16, group.size() * 2));
            for (Transaction t : group) {
                idIndex.merge(t.getID(), 1, Integer::sum);
            }
            indexSource = group;
            indexedAtModification = modifications();
        }
        return idIndex;
    }

    private void decrementIndex(long txID, int by) {
        Integer c = idIndex.get(txID);
        if (c == null) return;
        if (c <= by) {
            idIndex.remove(txID);
        } else {
            idIndex.put(txID, c - by);
        }
    }

    ////////// Accessors //////////

    /**
     * See {@linkplain ITxContainer#getID()}.
     */
    @Override
    public int getID() {
        return groupID;
    }

    /**
     * See {@linkplain ITxContainer#getCount()}.
     */
    @Override
    public int getCount() {
        return (group.size());
    }

    /**
     * See {@linkplain ITxContainer#getSize()}.
     */
    @Override
    public float getSize() {
        return totalSize;
    }

    /**
     * See {@linkplain ITxContainer#getValue()}.
     */
    @Override
    public float getValue() {
        return totalValue;
    }

    /**
     * Return the ArrayList of transactions in the group
     *
     * @return An <tt>ArrayList</tt> of <tt>Transaction</tt> objects representing the transactions in the group.
     */
    @Override
    public List<Transaction> getTransactions() {
        return group;
    }

    /**
     * Get the transaction of the group at index <tt>index</tt>. Does not check if index exists.
     *
     * @param index The index from <tt>0</tt> to <tt>n-1</tt>
     * @return A reference to the <tt>Transaction</tt> object.
     */
    public Transaction getTransaction(int index) {
        return group.get(index);
    }

    public Transaction getTransactionById(int txID) {
        for (Transaction r : group) {
            if (r.getID() == txID) {
            	return (r);
            }
        }
    	return null;
    }


    ////////// Print Group //////////

    /**
     * See {@linkplain ITxContainer#printIDs(String)}.
     */
    @Override
    public String printIDs(String sep) {
        StringBuilder s = new StringBuilder("{");
        for (Transaction t : group) {
            s.append(t.getID()).append(sep);
        }
        if (s.length() > 1)
            s = new StringBuilder(s.substring(0, s.length() - 1) + "}");
        else
            s.append("}");
        return (s.toString());
    }

    /**
     * Generates a debug printout of the Transaction IDs in the pool.
     *
     * @return A string containing the IDs of the Transactions in the pool, separated by commas.
     */
    @SuppressWarnings("unused")
    public String debugPrintPoolTx() {
        StringBuilder s = new StringBuilder();
        for (Transaction t : group) {
            s.append(t.getID()).append(", ");
        }
        return (s.toString());
    }

}
