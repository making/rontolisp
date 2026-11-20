import java.util.*;
import java.util.stream.*;

// BitSet.stream().spliterator(): when the size it tells is counted, what a part walks
// after the set changed.
public class BitSplit2 {
    public static void main(String[] args) {
        BitSet a = new BitSet();
        a.set(1, 4);
        Spliterator.OfInt s = a.stream().spliterator();
        a.set(10);
        System.out.println("est after change " + s.estimateSize());
        BitSet b = new BitSet();
        b.set(0, 40);
        Spliterator.OfInt t = b.stream().spliterator();
        Spliterator.OfInt p = t.trySplit();
        b.set(45);
        b.set(5000);
        b.clear(1);
        List<Integer> rest = new ArrayList<>();
        t.forEachRemaining((int i) -> rest.add(i));
        List<Integer> pre = new ArrayList<>();
        p.forEachRemaining((int i) -> pre.add(i));
        System.out.println(pre + " " + rest);
        BitSet c = new BitSet();
        c.set(2, 6);
        Spliterator.OfInt u = c.stream().spliterator();
        u.tryAdvance((int i) -> System.out.println("u " + i));
        Spliterator.OfInt v = u.trySplit();
        System.out.println(u.estimateSize() + " " + (v == null ? "null" : v.estimateSize()));
        List<Integer> ur = new ArrayList<>();
        u.forEachRemaining((int i) -> ur.add(i));
        List<Integer> vr = new ArrayList<>();
        if (v != null) v.forEachRemaining((int i) -> vr.add(i));
        System.out.println(vr + " " + ur);
        BitSet d = new BitSet();
        d.set(0, 8);
        Spliterator.OfInt w = d.stream().spliterator();
        w.forEachRemaining((int i) -> { });
        System.out.println("after walk " + w.estimateSize() + " " + w.trySplit() + " " + w.tryAdvance((int i) -> { }));
        BitSet e = new BitSet();
        e.set(Integer.MAX_VALUE - 1);
        e.set(Integer.MAX_VALUE - 3);
        System.out.println(e.stream().boxed().collect(Collectors.toList()) + " " + e.length() + " " + e.nextClearBit(Integer.MAX_VALUE - 1) + " " + e.previousSetBit(Integer.MAX_VALUE));
        BitSet f = new BitSet();
        f.set(Integer.MAX_VALUE);
        System.out.println(f.stream().boxed().collect(Collectors.toList()) + " " + f.nextSetBit(5) + " " + f.nextClearBit(Integer.MAX_VALUE) + " " + f.previousClearBit(Integer.MAX_VALUE) + " " + f.get(Integer.MAX_VALUE));
        Spliterator.OfInt g = f.stream().spliterator();
        System.out.println(g.estimateSize() + " " + g.trySplit());
    }
}
