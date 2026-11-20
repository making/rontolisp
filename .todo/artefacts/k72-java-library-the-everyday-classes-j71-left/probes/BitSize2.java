import java.util.*;

// BitSet.size(): when a size given to the constructor stops being kept by a copy.
public class BitSize2 {
    static int cloned(BitSet b) {
        return ((BitSet) b.clone()).size();
    }

    public static void main(String[] args) {
        BitSet a = new BitSet(10);
        a.set(500);
        a.clear(500);
        System.out.println("given, grown, cleared: " + a.size() + " clone " + cloned(a));
        BitSet b = new BitSet(1000);
        System.out.println("given 1000 empty: clone " + cloned(b));
        BitSet c = new BitSet(1000);
        c.set(5);
        c.set(2000);
        c.clear(2000);
        System.out.println("given 1000, grown, cleared: " + c.size() + " clone " + cloned(c));
        BitSet d = BitSet.valueOf(new long[] {7, 0, 9});
        d.clear(129);
        d.clear(128);
        d.clear(131);
        System.out.println("valueOf cleared: " + d.size() + " clone " + cloned(d));
        BitSet h = BitSet.valueOf(new long[] {1, 2, 3, 4});
        BitSet g = h.get(0, 70);
        g.clear(65);
        System.out.println("get: " + g.size() + " clone " + cloned(g));
        BitSet e = new BitSet();
        e.set(300);
        BitSet f = (BitSet) e.clone();
        f.clear(300);
        System.out.println("clone of clone: " + f.size() + " " + cloned(f));
        BitSet i = new BitSet(100);
        BitSet j = (BitSet) i.clone();
        System.out.println("sticky clone sticky: " + j.size() + " " + cloned(j));
        j.set(1);
        j.clear(1);
        System.out.println("  " + cloned(j));
        BitSet k = new BitSet(100);
        k.set(70);
        k.and(new BitSet());
        System.out.println("given, and: " + k.size() + " clone " + cloned(k));
        BitSet l = new BitSet(100);
        l.set(1000);
        l.clear(1000);
        BitSet m = new BitSet(100);
        m.set(10);
        m.xor(l);
        System.out.println("xor of grown: " + m.size() + " " + cloned(m));
    }
}
