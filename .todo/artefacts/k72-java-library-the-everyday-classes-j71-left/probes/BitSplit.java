import java.util.*;
import java.util.stream.*;

// BitSet.stream().spliterator(): what trySplit hands off, and the sizes each part tells.
public class BitSplit {
    static String walk(Spliterator.OfInt s, int depth) {
        String head = "[" + s.estimateSize() + "/" + s.getExactSizeIfKnown() + "/" + s.characteristics() + "]";
        if (depth == 0) {
            List<Integer> rest = new ArrayList<>();
            s.forEachRemaining((int i) -> rest.add(i));
            return head + rest;
        }
        Spliterator.OfInt prefix = s.trySplit();
        if (prefix == null) {
            List<Integer> rest = new ArrayList<>();
            s.forEachRemaining((int i) -> rest.add(i));
            return head + "x" + rest;
        }
        return head + "(" + walk(prefix, depth - 1) + " | " + walk(s, depth - 1) + ")";
    }

    static BitSet of(int... bits) {
        BitSet b = new BitSet();
        for (int i : bits) {
            b.set(i);
        }
        return b;
    }

    public static void main(String[] args) {
        List<BitSet> sets = new ArrayList<>();
        sets.add(new BitSet());
        sets.add(of(5));
        sets.add(of(0, 1));
        sets.add(of(0, 2, 4, 63, 64, 65, 127, 200));
        sets.add(of(1000));
        sets.add(of(3, 1000));
        sets.add(of(3, 4, 5, 6, 7, 8, 9, 10));
        BitSet r = new BitSet();
        r.set(0, 40);
        sets.add(r);
        BitSet q = new BitSet();
        q.set(100, 140);
        q.set(500);
        sets.add(q);
        for (BitSet b : sets) {
            System.out.println(b + " -> " + walk(b.stream().spliterator(), 3));
        }
        BitSet a = of(1, 2, 3);
        Spliterator.OfInt s = a.stream().spliterator();
        System.out.println("before " + s.estimateSize());
        a.set(10);
        a.set(11);
        System.out.println("after set " + s.estimateSize());
        s.tryAdvance((int i) -> System.out.println("got " + i));
        System.out.println("advanced " + s.estimateSize());
        a.set(20);
        List<Integer> rest = new ArrayList<>();
        s.forEachRemaining((int i) -> rest.add(i));
        System.out.println("rest " + rest);
        BitSet c = of(1, 2, 3);
        IntStream st = c.stream();
        c.set(7);
        System.out.println("late " + st.boxed().collect(Collectors.toList()));
        BitSet d = of(1, 2, 3);
        Spliterator.OfInt ds = d.stream().spliterator();
        ds.tryAdvance((int i) -> System.out.println("d got " + i));
        d.set(50);
        System.out.println("d est " + ds.estimateSize());
        List<Integer> drest = new ArrayList<>();
        ds.forEachRemaining((int i) -> drest.add(i));
        System.out.println("d rest " + drest);
    }
}
