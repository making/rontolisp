import java.util.*;
import java.util.function.*;

public class PQMore {
    static final class E {
        final int key; final String tag;
        E(int key, String tag) { this.key = key; this.tag = tag; }
        public boolean equals(Object o) { return o instanceof E e && e.key == key; }
        public int hashCode() { return key; }
        public String toString() { return key + tag; }
    }

    public static void main(String[] args) {
        StringBuilder b = new StringBuilder();
        PriorityQueue<Integer> c = new PriorityQueue<>(List.of(5, 3, 8, 1, 9, 2));
        try { c.forEach(x -> { b.append(x); if (x == 3) c.offer(0); }); } catch (ConcurrentModificationException e) { b.append(" CME"); }
        System.out.println("forEach: " + b + " " + c);
        b.setLength(0);
        PriorityQueue<Integer> d = new PriorityQueue<>(List.of(5, 3, 8, 1, 9, 2));
        try { d.removeIf(x -> { b.append(x); if (x == 3) d.offer(0); return x == 8; }); } catch (ConcurrentModificationException e) { b.append(" CME"); }
        System.out.println("removeIf: " + b + " " + d);
        PriorityQueue<Object> m = new PriorityQueue<>();
        m.add(1);
        try { m.add("a"); } catch (ClassCastException e) { System.out.println("after a failed add: " + m + " " + m.size()); }
        try { m.add(new Object()); } catch (ClassCastException e) { System.out.println("after a failed add of an object: " + m + " " + m.size()); }
        PriorityQueue<Integer> t = new PriorityQueue<>((x, y) -> { if (x == 7 || y == 7) throw new IllegalStateException(); return x - y; });
        t.addAll(List.of(5, 3, 8));
        try { t.add(7); } catch (IllegalStateException e) { System.out.println("after a throwing compare: " + t + " " + t.size()); }
        // the iterator removing an element it gave after the array: by identity or equality
        PriorityQueue<E> q = new PriorityQueue<>(Comparator.comparingInt((E x) -> x.key));
        for (E e : List.of(new E(1, "a"), new E(5, "b"), new E(2, "c"), new E(6, "d"), new E(7, "e"), new E(3, "f"), new E(4, "g"), new E(3, "h"))) q.add(e);
        System.out.println("q " + q);
        StringBuilder w = new StringBuilder();
        Iterator<E> it = q.iterator();
        int i = 0;
        while (it.hasNext()) {
            E e = it.next();
            w.append(e).append(' ');
            if (e.key == 6 || e.key == 7 || e.tag.equals("h") || e.tag.equals("f")) { it.remove(); w.append("x "); }
            i++;
        }
        System.out.println("walk " + w + "| " + q);
        // remove twice after a later element, next after the end, remove after the end
        PriorityQueue<Integer> r = new PriorityQueue<>(List.of(1, 9, 2, 8, 3, 7, 4));
        Iterator<Integer> ri = r.iterator();
        StringBuilder rw = new StringBuilder();
        while (ri.hasNext()) { int x = ri.next(); rw.append(x); if (x == 8 || x == 7 || x == 4) { ri.remove(); rw.append('x'); } rw.append(' '); }
        try { ri.remove(); } catch (IllegalStateException e) { rw.append("ISE "); }
        try { ri.next(); } catch (NoSuchElementException e) { rw.append("NSEE"); }
        System.out.println("walk2 " + rw + " | " + r);
        PriorityQueue<Integer> s = new PriorityQueue<>(List.of(4, 1, 3, 2));
        Iterator<Integer> si = s.iterator();
        si.next();
        s.remove(3);
        try { si.next(); } catch (ConcurrentModificationException e) { System.out.println("remove(o) during a walk: CME"); }
        PriorityQueue<Integer> u = new PriorityQueue<>(List.of(4, 1, 3, 2));
        Iterator<Integer> ui = u.iterator();
        ui.next();
        u.remove(99);
        System.out.println("an absent remove: " + ui.next());
        PriorityQueue<Integer> v = new PriorityQueue<>(List.of(4, 1, 3, 2));
        Iterator<Integer> vi = v.iterator();
        vi.next();
        v.removeIf(x -> false);
        try { System.out.println("removeIf of nothing: " + vi.next()); } catch (RuntimeException e) { System.out.println("removeIf of nothing: " + e.getClass().getSimpleName()); }
        PriorityQueue<Integer> va = new PriorityQueue<>(List.of(4, 1, 3, 2));
        Iterator<Integer> vai = va.iterator();
        vai.next();
        va.removeAll(List.of());
        try { System.out.println("removeAll of nothing: " + vai.next()); } catch (RuntimeException e) { System.out.println("removeAll of nothing: " + e.getClass().getSimpleName()); }
        PriorityQueue<Integer> vb = new PriorityQueue<>(List.of(4, 1, 3, 2));
        Iterator<Integer> vbi = vb.iterator();
        vbi.next();
        vb.retainAll(List.of(1, 2, 3, 4));
        try { System.out.println("retainAll of all: " + vbi.next()); } catch (RuntimeException e) { System.out.println("retainAll of all: " + e.getClass().getSimpleName()); }
        PriorityQueue<Integer> vc = new PriorityQueue<>(List.of(4, 1, 3, 2));
        Iterator<Integer> vci = vc.iterator();
        vci.next();
        vc.poll();
        try { System.out.println("poll: " + vci.next()); } catch (RuntimeException e) { System.out.println("poll: " + e.getClass().getSimpleName()); }
        PriorityQueue<Integer> vd = new PriorityQueue<>(List.of(4, 1, 3, 2));
        Iterator<Integer> vdi = vd.iterator();
        vdi.next();
        vd.addAll(List.of());
        vd.contains(1);
        vd.peek();
        vd.toArray();
        try { System.out.println("reads: " + vdi.next()); } catch (RuntimeException e) { System.out.println("reads: " + e.getClass().getSimpleName()); }
        PriorityQueue<Integer> ve = new PriorityQueue<>();
        Iterator<Integer> vei = ve.iterator();
        ve.poll();
        ve.clear();
        try { System.out.println("an empty poll and clear: " + vei.hasNext()); ve.add(1); vei.next(); System.out.println("next"); } catch (RuntimeException e) { System.out.println("an empty poll and clear: " + e.getClass().getSimpleName()); }
        PriorityQueue<Integer> y = new PriorityQueue<>(List.of(4, 1, 3, 2));
        Iterator<Integer> yi = y.iterator();
        yi.next();
        y.clear();
        try { System.out.println("clear: " + yi.hasNext() + " " + yi.next()); } catch (RuntimeException e) { System.out.println("clear: " + e.getClass().getSimpleName()); }
        PriorityQueue<Integer> z = new PriorityQueue<>();
        Iterator<Integer> zi = z.iterator();
        z.clear();
        z.poll();
        try { System.out.println("empty clear: " + zi.hasNext()); zi.next(); } catch (RuntimeException e) { System.out.println("empty clear: " + e.getClass().getSimpleName()); }
    }
}
