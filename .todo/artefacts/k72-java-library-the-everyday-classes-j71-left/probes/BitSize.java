import java.util.*;

// BitSet.size(): the bits of space a set holds after each kind of change.
public class BitSize {
    public static void main(String[] args) {
        BitSet a = new BitSet();
        StringBuilder b = new StringBuilder("grow:");
        for (int i : new int[] {0, 63, 64, 127, 128, 200, 255, 256, 300, 600, 1000, 5000, 70, 9000, 20000}) {
            a.set(i);
            b.append(' ').append(i).append('=').append(a.size());
        }
        System.out.println(b);
        for (int n : new int[] {0, 1, 63, 64, 65, 128, 129, 1000}) {
            BitSet c = new BitSet(n);
            String s = "new(" + n + ") " + c.size();
            c.set(n + 500);
            s += " set(" + (n + 500) + ") " + c.size();
            c.set(n + 5000);
            s += " " + c.size();
            System.out.println(s + " clone " + ((BitSet) c.clone()).size());
        }
        BitSet d = new BitSet();
        d.set(1000);
        d.clear(1000);
        System.out.println("cleared " + d.size() + " clone " + ((BitSet) d.clone()).size() + " " + d.length());
        BitSet e = new BitSet();
        e.set(5);
        e.set(700);
        System.out.println("clone " + e.size() + " " + ((BitSet) e.clone()).size());
        e.clear(700);
        System.out.println("clone after clear " + e.size() + " " + ((BitSet) e.clone()).size());
        BitSet f = new BitSet(2000);
        f.set(3);
        System.out.println("sticky clone " + ((BitSet) f.clone()).size());
        System.out.println("valueOf " + BitSet.valueOf(new long[] {1, 0, 0}).size() + " " + BitSet.valueOf(new long[] {0, 0, 5}).size() + " " + BitSet.valueOf(new long[0]).size() + " " + BitSet.valueOf(new byte[] {1, 2, 3, 0, 0}).size() + " " + BitSet.valueOf(new byte[9]).size() + " " + BitSet.valueOf(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 1}).size());
        BitSet g = BitSet.valueOf(new long[] {1, 0, 0});
        g.set(64);
        System.out.println("valueOf grown " + g.size());
        BitSet h = BitSet.valueOf(new long[] {1, 2, 3, 4});
        System.out.println("get range " + h.get(0, 64).size() + " " + h.get(0, 200).size() + " " + h.get(10, 20).size() + " " + h.get(300, 400).size() + " " + h.get(0, 0).size());
        BitSet i = new BitSet();
        BitSet j = new BitSet();
        j.set(500);
        i.or(j);
        System.out.println("or " + i.size());
        BitSet k = new BitSet();
        k.xor(j);
        System.out.println("xor " + k.size());
        BitSet l = new BitSet();
        l.and(j);
        System.out.println("and " + l.size());
        BitSet m = new BitSet();
        m.andNot(j);
        System.out.println("andNot " + m.size());
        BitSet n = new BitSet();
        n.set(10, 700);
        System.out.println("set range " + n.size());
        BitSet o = new BitSet();
        o.flip(10, 700);
        System.out.println("flip range " + o.size());
        BitSet p = new BitSet();
        p.flip(700);
        System.out.println("flip " + p.size());
        BitSet q = new BitSet();
        q.clear(10, 700);
        System.out.println("clear range " + q.size());
        BitSet r = new BitSet();
        r.set(700, false);
        System.out.println("set false " + r.size());
        BitSet s = new BitSet();
        s.set(100, 100);
        System.out.println("set empty range " + s.size());
        BitSet t = new BitSet(0);
        t.set(0);
        System.out.println("zero then set " + t.size());
        BitSet u = new BitSet(10);
        u.or(j);
        System.out.println("sticky or " + u.size() + " clone " + ((BitSet) u.clone()).size());
        BitSet v = new BitSet();
        v.set(130);
        v.and(new BitSet());
        System.out.println("and empty " + v.size() + " " + v.length() + " clone " + ((BitSet) v.clone()).size());
        BitSet w = new BitSet();
        w.set(130);
        w.clear();
        System.out.println("clear all " + w.size() + " clone " + ((BitSet) w.clone()).size());
        BitSet x = new BitSet();
        x.set(130);
        BitSet y = new BitSet();
        y.set(5000);
        x.or(y);
        System.out.println("or grows " + x.size());
    }
}
