import java.util.*;
import java.util.stream.*;

// BitSet: the members over a few sets, the scans from each index, the refusals.
public class Bits {
    static void tryIt(String what, java.util.concurrent.Callable<Object> r) {
        try {
            System.out.println(what + ": " + r.call());
        } catch (Exception e) {
            System.out.println(what + ": " + e.getClass().getName() + " " + e.getMessage());
        }
    }

    static BitSet of(int... bits) {
        BitSet b = new BitSet();
        for (int i : bits) {
            b.set(i);
        }
        return b;
    }

    public static void main(String[] args) {
        BitSet a = of(0, 2, 4, 63, 64, 65, 127, 200);
        BitSet b = of(1, 2, 3, 64, 128, 300);
        BitSet e = new BitSet();
        System.out.println(a + " " + a.length() + " " + a.cardinality() + " " + a.isEmpty() + " " + a.hashCode());
        System.out.println(b + " " + b.length() + " " + b.cardinality() + " " + b.hashCode());
        System.out.println(e + " " + e.length() + " " + e.cardinality() + " " + e.isEmpty() + " " + e.hashCode());
        System.out.println(Arrays.toString(a.toLongArray()) + " " + Arrays.toString(a.toByteArray()));
        System.out.println(Arrays.toString(e.toLongArray()) + " " + Arrays.toString(e.toByteArray()));
        System.out.println(Arrays.toString(of(7, 8, 15, 16, 63).toByteArray()) + " " + Arrays.toString(of(63).toLongArray()));
        System.out.println(BitSet.valueOf(new long[] {-1L, 0x8000000000000000L}) + " " + BitSet.valueOf(new byte[] {(byte) 0x81, 0, (byte) 0xff}));
        System.out.println(BitSet.valueOf(new long[] {5, 0, 0}).equals(of(0, 2)) + " " + BitSet.valueOf(new long[] {-1L}).hashCode() + " " + of(63).hashCode() + " " + of(64).hashCode() + " " + of(1000).hashCode());
        for (String op : new String[] {"and", "or", "xor", "andNot"}) {
            BitSet x = (BitSet) a.clone();
            switch (op) {
                case "and" -> x.and(b);
                case "or" -> x.or(b);
                case "xor" -> x.xor(b);
                default -> x.andNot(b);
            }
            System.out.println(op + " " + x + " " + x.length() + " " + x.cardinality() + " " + a.intersects(b) + " " + x.equals(a));
        }
        BitSet self = (BitSet) a.clone();
        self.and(self);
        System.out.print("self and " + self);
        self.or(self);
        System.out.print(" or " + self);
        self.xor(self);
        System.out.print(" xor " + self);
        BitSet self2 = (BitSet) a.clone();
        self2.andNot(self2);
        System.out.println(" andNot " + self2);
        StringBuilder scans = new StringBuilder();
        for (int i = -1; i <= 305; i++) {
            if (i < 0) {
                scans.append(" prev").append(a.previousSetBit(-1)).append(',').append(a.previousClearBit(-1));
                continue;
            }
            scans.append(' ').append(i).append(':').append(a.nextSetBit(i)).append(',').append(a.nextClearBit(i)).append(',').append(a.previousSetBit(i)).append(',').append(a.previousClearBit(i)).append(',').append(a.get(i) ? 1 : 0);
        }
        System.out.println(scans);
        BitSet full = new BitSet();
        full.set(0, 128);
        System.out.println(full.nextClearBit(0) + " " + full.nextClearBit(127) + " " + full.previousClearBit(127) + " " + full.nextSetBit(128) + " " + full.previousSetBit(500) + " " + e.previousSetBit(10) + " " + e.nextSetBit(0) + " " + e.nextClearBit(5) + " " + e.previousClearBit(5));
        System.out.println(a.get(0, 300) + " " + a.get(2, 66) + " " + a.get(63, 64) + " " + a.get(100, 100) + " " + a.get(201, 900) + " " + a.get(60, 130));
        BitSet r = new BitSet();
        r.set(3, 70);
        System.out.print("set range " + r.cardinality() + " " + r.length());
        r.clear(10, 65);
        System.out.print(" clear " + r);
        r.flip(0, 12);
        System.out.print(" flip " + r);
        r.set(5, 8, false);
        System.out.print(" false " + r);
        r.set(100, 103, true);
        System.out.print(" true " + r);
        r.flip(101);
        r.set(2, false);
        r.set(400, true);
        System.out.print(" " + r);
        r.clear(65, 1000);
        System.out.println(" " + r + " " + r.length());
        r.clear();
        System.out.println("cleared " + r + " " + r.isEmpty() + " " + r.length());
        System.out.println(a.stream().boxed().collect(Collectors.toList()) + " " + a.stream().sum() + " " + e.stream().count());
        Spliterator.OfInt sp = a.stream().spliterator();
        System.out.println("spliterator " + sp.characteristics() + " " + sp.estimateSize() + " " + sp.getExactSizeIfKnown());
        BitSet big = new BitSet();
        big.set(0, 1000);
        Spliterator.OfInt sp2 = big.stream().spliterator();
        Spliterator.OfInt sp3 = sp2.trySplit();
        System.out.println("split " + (sp3 == null ? "null" : sp3.estimateSize() + " " + sp3.characteristics()) + " " + sp2.estimateSize());
        System.out.println(a.equals(a.clone()) + " " + a.equals(b) + " " + a.equals(null) + " " + a.equals("x") + " " + new BitSet(500).equals(new BitSet()));
        tryIt("new -1", () -> new BitSet(-1));
        tryIt("set -1", () -> { new BitSet().set(-1); return null; });
        tryIt("get -1", () -> new BitSet().get(-1));
        tryIt("clear -1", () -> { new BitSet().clear(-1); return null; });
        tryIt("flip -1", () -> { new BitSet().flip(-1); return null; });
        tryIt("set false -1", () -> { new BitSet().set(-1, false); return null; });
        tryIt("nextSet -1", () -> new BitSet().nextSetBit(-1));
        tryIt("nextClear -1", () -> new BitSet().nextClearBit(-1));
        tryIt("prevSet -2", () -> new BitSet().previousSetBit(-2));
        tryIt("prevClear -2", () -> new BitSet().previousClearBit(-2));
        tryIt("set -1 -2", () -> { new BitSet().set(-1, -2); return null; });
        tryIt("set 1 -2", () -> { new BitSet().set(1, -2); return null; });
        tryIt("set 5 3", () -> { new BitSet().set(5, 3); return null; });
        tryIt("set 5 3 true", () -> { new BitSet().set(5, 3, true); return null; });
        tryIt("set 5 3 false", () -> { new BitSet().set(5, 3, false); return null; });
        tryIt("clear 5 3", () -> { new BitSet().clear(5, 3); return null; });
        tryIt("clear -1 3", () -> { new BitSet().clear(-1, 3); return null; });
        tryIt("flip 5 3", () -> { new BitSet().flip(5, 3); return null; });
        tryIt("get 5 3", () -> new BitSet().get(5, 3));
        tryIt("get -1 3", () -> new BitSet().get(-1, 3));
        tryIt("and null", () -> { new BitSet().and(null); return null; });
        tryIt("or null", () -> { new BitSet().or(null); return null; });
        tryIt("valueOf null", () -> BitSet.valueOf((long[]) null));
        tryIt("intersects null", () -> new BitSet().intersects(null));
        tryIt("set max", () -> { BitSet m = new BitSet(); m.set(Integer.MAX_VALUE); return m.length() + " " + m.size() + " " + m.cardinality(); });
        tryIt("clear 5 5", () -> { BitSet m = of(5); m.clear(5, 5); return m; });
        tryIt("clear 0 0", () -> { BitSet m = new BitSet(); m.clear(0, 0); return m; });
    }
}
