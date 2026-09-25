package ca.yorku.cmg.cnsim.engine.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The sorted view and ID index inside {@link TransactionGroup} must never change a result, only
 * how fast it is computed. These tests drive a group through random interleavings of the
 * operations the simulator uses and compare every answer with the original algorithms.
 */
class TransactionGroupCacheTest {

    private static final Comparator<Transaction> FEE_RATE = new TxValuePerSizeComparator();

    /** getTopN exactly as it was before the sorted view existed. */
    private static List<Long> legacyTopN(List<Transaction> group, float sizeLimit, Comparator<Transaction> comp) {
        List<Transaction> sorted = group.stream().sorted(comp).toList();
        ArrayList<Transaction> result = new ArrayList<>();
        int i = 0;
        float sum = 0;
        while ((sum <= sizeLimit) && (i < sorted.size())) {
            sum += sorted.get(i).getSize();
            result.add(sorted.get(i));
            i++;
        }
        if (sum > sizeLimit) {
            result.remove(i - 1);
        }
        return ids(result);
    }

    private static List<Long> ids(List<Transaction> txs) {
        List<Long> out = new ArrayList<>();
        for (Transaction t : txs) {
            out.add(t.getID());
        }
        return out;
    }

    private static Transaction randomTx(Random rnd, long id, boolean coarse) {
        // Coarse values make equal fee rates (ties) likely, exercising the fallback path.
        float value = coarse ? 10 * (1 + rnd.nextInt(4)) : 100 + rnd.nextFloat() * 1000;
        float size = coarse ? 10 * (1 + rnd.nextInt(4)) : 50 + rnd.nextInt(400);
        return new Transaction(id, 0, value, size);
    }

    private void runRandomScenario(long seed, boolean coarse) {
        Random rnd = new Random(seed);
        TransactionGroup pool = new TransactionGroup();
        List<Transaction> reference = new ArrayList<>();
        long nextId = 1;
        float limit = 3000;

        for (int step = 0; step < 600; step++) {
            int op = rnd.nextInt(10);
            if (op < 5 || reference.isEmpty()) {
                Transaction t = randomTx(rnd, nextId++, coarse);
                pool.addTransaction(t);
                reference.add(t);
            } else if (op == 5) {
                Transaction t = reference.get(rnd.nextInt(reference.size()));
                pool.removeTransaction(t);
                reference.remove(t);
            } else if (op == 6) {
                // A block's worth of transactions leaves the pool.
                TransactionGroup block = new TransactionGroup();
                for (Transaction t : reference) {
                    if (rnd.nextInt(3) == 0) block.addTransaction(t);
                }
                pool.extractGroup(block);
                reference.removeAll(block.getTransactions());
            } else if (op == 7) {
                // Callers may mutate the list directly; the caches must notice.
                if (!pool.getTransactions().isEmpty()) {
                    Transaction t = pool.getTransactions().remove(0);
                    reference.remove(t);
                }
            } else if (op == 8) {
                List<Transaction> some = new ArrayList<>();
                for (Transaction t : reference) {
                    if (rnd.nextInt(4) == 0) some.add(t);
                }
                pool.removeAllOf(some);
                reference.removeAll(some);
            } else {
                long probe = 1 + rnd.nextInt((int) nextId);
                boolean expected = reference.stream().anyMatch(t -> t.getID() == probe);
                assertEquals(expected, pool.contains(probe), "contains(" + probe + ") at step " + step);
            }

            assertEquals(ids(reference), ids(pool.getTransactions()), "list order at step " + step);
            assertEquals(legacyTopN(reference, limit, FEE_RATE), ids(pool.getTopN(limit, FEE_RATE).getTransactions()),
                    "getTopN at step " + step + " (seed " + seed + ")");
        }
    }

    @Test
    void topNMatchesLegacySortUnderRandomOperations() {
        for (long seed = 1; seed <= 20; seed++) {
            runRandomScenario(seed, false);
        }
    }

    @Test
    void topNMatchesLegacySortWhenFeeRatesTie() {
        for (long seed = 1; seed <= 20; seed++) {
            runRandomScenario(seed, true);
        }
    }

    @Test
    void extractGroupMatchesOneByOneRemovalIncludingDuplicatesAndTotals() {
        Random rnd = new Random(7);
        for (int round = 0; round < 50; round++) {
            List<Transaction> base = new ArrayList<>();
            for (int i = 0; i < 30; i++) base.add(randomTx(rnd, i + 1, false));
            // Duplicate a few objects in the group and in the block.
            List<Transaction> content = new ArrayList<>(base);
            for (int i = 0; i < 5; i++) content.add(base.get(rnd.nextInt(base.size())));
            List<Transaction> blockContent = new ArrayList<>();
            for (int i = 0; i < 15; i++) blockContent.add(base.get(rnd.nextInt(base.size())));

            TransactionGroup fast = new TransactionGroup();
            TransactionGroup slow = new TransactionGroup(new ArrayList<>());
            for (Transaction t : content) {
                fast.addTransaction(t);
                slow.addTransaction(t);
            }
            fast.getTopN(1e9f, FEE_RATE); // build the sorted view so it is maintained too
            TransactionGroup block = new TransactionGroup(new ArrayList<>(blockContent));

            fast.extractGroup(block);
            for (Transaction t : block.getTransactions()) {
                slow.removeTransaction(t); // the original loop
            }
            assertEquals(ids(slow.getTransactions()), ids(fast.getTransactions()));
            assertEquals(slow.getSize(), fast.getSize());
            assertEquals(slow.getValue(), fast.getValue());
            assertEquals(legacyTopN(slow.getTransactions(), 1e9f, FEE_RATE),
                    ids(fast.getTopN(1e9f, FEE_RATE).getTransactions()));
        }
    }

    @Test
    void removeAllOfRemovesEveryOccurrenceAndKeepsTotals() {
        Transaction a = new Transaction(1, 0, 10, 100);
        Transaction b = new Transaction(2, 0, 20, 200);
        Transaction c = new Transaction(3, 0, 30, 300);
        TransactionGroup g = new TransactionGroup();
        g.addTransaction(a);
        g.addTransaction(b);
        g.addTransaction(a);
        g.addTransaction(c);

        g.removeAllOf(List.of(a));

        assertEquals(List.of(2L, 3L), ids(g.getTransactions()));
        assertEquals(500f, g.getSize());
        assertEquals(50f, g.getValue());
        assertFalse(g.contains(1));
        assertTrue(g.contains(3));
    }

    @Test
    void overlapChecksAgreeWithDefinition() {
        Transaction a = new Transaction(1, 0, 10, 100);
        Transaction b = new Transaction(2, 0, 20, 200);
        Transaction sameIdAsA = new Transaction(1, 0, 99, 99);
        TransactionGroup g1 = new TransactionGroup(new ArrayList<>(List.of(a, b)));
        TransactionGroup g2 = new TransactionGroup(new ArrayList<>(List.of(sameIdAsA)));
        TransactionGroup g3 = new TransactionGroup(new ArrayList<>(List.of(b)));
        TransactionGroup empty = new TransactionGroup();

        assertTrue(g1.overlapsWith(g2), "same ID overlaps");
        assertFalse(g1.overlapsWithByObj(g2), "different objects do not overlap by identity");
        assertTrue(g1.overlapsWithByObj(g3));
        assertFalse(g2.overlapsWith(g3));
        assertFalse(g1.overlapsWith(empty));
        assertFalse(empty.overlapsWith(g1));
    }
}
