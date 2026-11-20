import java.util.*;
import java.util.function.*;

public class Sub2 {
    static void t(String n, Supplier<Object> s) {
        try { System.out.println(n + " = " + s.get()); }
        catch (Throwable e) { System.out.println(n + " ! " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static List<Integer> fresh() { return new ArrayList<>(List.of(0, 1, 2, 3, 4, 5)); }
    static class Mine extends AbstractList<Integer> {
        final ArrayList<Integer> in = new ArrayList<>(List.of(0, 1, 2, 3));
        public Integer get(int i) { return in.get(i); }
        public int size() { return in.size(); }
        public void add(int i, Integer e) { in.add(i, e); modCount++; }
        public Integer remove(int i) { modCount++; return in.remove(i); }
        public Integer set(int i, Integer e) { return in.set(i, e); }
        void bump() { modCount += 2; }
        void setMc(int m) { modCount = m; }
    }
    static void rr(String n, List<Integer> l, Consumer<List<Integer>> op) {
        t(n + " get(9) stale", () -> { List<Integer> s = l.subList(1, 3); l.add(1); return s.get(9); });
        t(n + " set(9) stale", () -> { List<Integer> s = l.subList(1, 3); l.add(1); return s.set(9, 1); });
        t(n + " add(9) stale", () -> { List<Integer> s = l.subList(1, 3); l.add(1); s.add(9, 1); return 1; });
        t(n + " add(-1) stale", () -> { List<Integer> s = l.subList(1, 3); l.add(1); s.add(-1, 1); return 1; });
        t(n + " remove(9) stale", () -> { List<Integer> s = l.subList(1, 3); l.add(1); return s.remove(9); });
        t(n + " get(9)", () -> { List<Integer> s = l.subList(1, 3); return s.get(9); });
        t(n + " get(-1)", () -> { List<Integer> s = l.subList(1, 3); return s.get(-1); });
        t(n + " set(9)", () -> { List<Integer> s = l.subList(1, 3); return s.set(9, 1); });
        t(n + " add(9)", () -> { List<Integer> s = l.subList(1, 3); s.add(9, 1); return 1; });
        t(n + " add(-1)", () -> { List<Integer> s = l.subList(1, 3); s.add(-1, 1); return 1; });
        t(n + " remove(9)", () -> { List<Integer> s = l.subList(1, 3); return s.remove(9); });
        t(n + " listIterator(9)", () -> { List<Integer> s = l.subList(1, 3); return s.listIterator(9) != null; });
        t(n + " listIterator(-1)", () -> { List<Integer> s = l.subList(1, 3); return s.listIterator(-1) != null; });
        t(n + " listIterator(9) stale", () -> { List<Integer> s = l.subList(1, 3); l.add(1); return s.listIterator(9) != null; });
        t(n + " addAll(9) ", () -> { List<Integer> s = l.subList(1, 3); return s.addAll(9, List.of(1)); });
        t(n + " addAll(9) stale", () -> { List<Integer> s = l.subList(1, 3); l.add(1); return s.addAll(9, List.of(1)); });
        t(n + " addAll(1, empty) stale", () -> { List<Integer> s = l.subList(1, 3); l.add(1); return s.addAll(1, List.of()); });
        t(n + " addAll(empty) stale", () -> { List<Integer> s = l.subList(1, 3); l.add(1); return s.addAll(List.of()); });
        t(n + " addAll(1) stale", () -> { List<Integer> s = l.subList(1, 3); l.add(1); return s.addAll(List.of(1)); });
        t(n + " subList(0,9) stale", () -> { List<Integer> s = l.subList(1, 3); l.add(1); return s.subList(0, 9); });
        t(n + " subList(-1,1) stale", () -> { List<Integer> s = l.subList(1, 3); l.add(1); return s.subList(-1, 1); });
        t(n + " subList(2,1)", () -> { List<Integer> s = l.subList(1, 3); return s.subList(2, 1); });
        t(n + " subList(0,1) stale", () -> { List<Integer> s = l.subList(1, 3); l.add(1); return s.subList(0, 1); });
        t(n + " hasNext after stale", () -> { List<Integer> s = l.subList(1, 3); Iterator<Integer> i = s.iterator(); l.add(1); return i.hasNext(); });
        t(n + " iterator after the sublist grew by parent, remove", () -> { List<Integer> s = l.subList(1, 3); Iterator<Integer> i = s.iterator(); i.next(); l.add(1); i.remove(); return 1; });
        t(n + " listIterator hasPrevious/nextIndex stale", () -> { List<Integer> s = l.subList(1, 3); ListIterator<Integer> i = s.listIterator(1); l.add(1); return i.hasPrevious() + " " + i.nextIndex() + " " + i.previousIndex(); });
        t(n + " listIterator previous stale", () -> { List<Integer> s = l.subList(1, 3); ListIterator<Integer> i = s.listIterator(1); l.add(1); return i.previous(); });
        t(n + " listIterator set stale", () -> { List<Integer> s = l.subList(1, 3); ListIterator<Integer> i = s.listIterator(1); i.previous(); l.add(1); i.set(5); return 1; });
        t(n + " listIterator add stale", () -> { List<Integer> s = l.subList(1, 3); ListIterator<Integer> i = s.listIterator(1); l.add(1); i.add(5); return 1; });
        t(n + " listIterator set before next", () -> { List<Integer> s = l.subList(1, 3); ListIterator<Integer> i = s.listIterator(1); i.set(5); return 1; });
        t(n + " iterator remove before next stale", () -> { List<Integer> s = l.subList(1, 3); Iterator<Integer> i = s.iterator(); l.add(1); i.remove(); return 1; });
        t(n + " iterator next past end", () -> { List<Integer> s = l.subList(1, 3); Iterator<Integer> i = s.iterator(); i.next(); i.next(); i.next(); return 1; });
        t(n + " iterator next past end, stale", () -> { List<Integer> s = l.subList(1, 3); Iterator<Integer> i = s.iterator(); i.next(); i.next(); l.add(1); i.next(); return 1; });
        t(n + " iterator next past end, own add", () -> { List<Integer> s = l.subList(1, 3); Iterator<Integer> i = s.iterator(); i.next(); i.next(); s.add(1); i.next(); return 1; });
        t(n + " iterator sees parent removal at tail", () -> { List<Integer> s = l.subList(1, 3); Iterator<Integer> i = s.iterator(); i.next(); i.next(); l.remove(0); return i.hasNext(); });
        t(n + " sub.clear() size", () -> { List<Integer> s = l.subList(1, 3); s.clear(); return l.size() + " " + s.size(); });
        t(n + " sub.equals(sub)", () -> { List<Integer> s = l.subList(1, 3); return s.equals(s) + " " + s.equals(l.subList(1, 3)) ; });
        t(n + " sub.equals stale-other", () -> { List<Integer> s = l.subList(1, 3); List<Integer> u = l.subList(1, 3); l.add(1); return s.equals(u); });
        t(n + " sub.toString own", () -> { List<Integer> s = l.subList(1, 3); return s.toString() + s.hashCode(); });
    }
    public static void main(String[] a) {
        rr("AL", fresh(), l -> {});
        rr("LL", new LinkedList<>(fresh()), l -> {});
        Mine m = new Mine(); m.addAll(List.of(4, 5));
        rr("Mine", m, l -> {});
        Mine m2 = new Mine(); m2.addAll(List.of(4, 5));
        t("Mine modCount lowered after", () -> { List<Integer> s = m2.subList(1, 3); m2.setMc(-5); return s.get(0); });
        t("Mine modCount same value after change", () -> { List<Integer> s = m2.subList(1, 3); m2.setMc(m2.hashCode() * 0 + 7); return s.get(0); });
        t("Mine bump 2 then get", () -> { List<Integer> s = m2.subList(1, 3); m2.bump(); return s.get(0); });
        t("Mine sub of sub get after root", () -> { List<Integer> s = m2.subList(1, 5).subList(1, 3); m2.add(1); return s.get(0); });
        // ArrayList's own count
        t("AL ensure(2) iterator", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); Iterator<Integer> i = l.iterator(); l.ensureCapacity(2); return i.next(); });
        t("AL ensure(100) iterator", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); Iterator<Integer> i = l.iterator(); l.ensureCapacity(100); return i.next(); });
        t("AL ensure(8) when capacity 6 iterator", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); Iterator<Integer> i = l.iterator(); l.ensureCapacity(8); return i.next(); });
        t("AL ensure(0) iterator", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); Iterator<Integer> i = l.iterator(); l.ensureCapacity(0); return i.next(); });
        t("AL(10) ensure(5) iterator", () -> { ArrayList<Integer> l = new ArrayList<>(10); l.addAll(fresh()); Iterator<Integer> i = l.iterator(); l.ensureCapacity(5); return i.next(); });
        t("AL(10) ensure(10) iterator", () -> { ArrayList<Integer> l = new ArrayList<>(10); l.addAll(fresh()); Iterator<Integer> i = l.iterator(); l.ensureCapacity(10); return i.next(); });
        t("AL(10) ensure(11) iterator", () -> { ArrayList<Integer> l = new ArrayList<>(10); l.addAll(fresh()); Iterator<Integer> i = l.iterator(); l.ensureCapacity(11); return i.next(); });
        t("AL trim exact iterator", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); Iterator<Integer> i = l.iterator(); l.trimToSize(); return i.next(); });
        t("AL trim loose iterator", () -> { ArrayList<Integer> l = new ArrayList<>(10); l.addAll(fresh()); Iterator<Integer> i = l.iterator(); l.trimToSize(); return i.next(); });
        t("AL trim empty iterator", () -> { ArrayList<Integer> l = new ArrayList<>(); Iterator<Integer> i = l.iterator(); l.trimToSize(); return i.hasNext(); });
        t("AL replaceAll iterator", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); Iterator<Integer> i = l.iterator(); l.replaceAll(x -> x); return i.next(); });
        t("AL addAll empty iterator", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); Iterator<Integer> i = l.iterator(); l.addAll(List.of()); return i.next(); });
        t("AL addAll(1, empty) iterator", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); Iterator<Integer> i = l.iterator(); l.addAll(1, List.of()); return i.next(); });
        t("AL clear empty iterator", () -> { ArrayList<Integer> l = new ArrayList<>(); Iterator<Integer> i = l.iterator(); l.clear(); return i.hasNext(); });
        t("AL clear empty iterator next", () -> { ArrayList<Integer> l = new ArrayList<>(); Iterator<Integer> i = l.iterator(); l.clear(); return i.next(); });
        t("AL removeIf false iterator", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); Iterator<Integer> i = l.iterator(); l.removeIf(x -> false); return i.next(); });
        t("AL removeAll none iterator", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); Iterator<Integer> i = l.iterator(); l.removeAll(List.of(9)); return i.next(); });
        t("AL retainAll all iterator", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); Iterator<Integer> i = l.iterator(); l.retainAll(fresh()); return i.next(); });
        t("AL sort iterator", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); Iterator<Integer> i = l.iterator(); l.sort(null); return i.next(); });
        t("AL set iterator", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); Iterator<Integer> i = l.iterator(); l.set(0, 1); return i.next(); });
        t("AL sort empty iterator", () -> { ArrayList<Integer> l = new ArrayList<>(); Iterator<Integer> i = l.iterator(); l.sort(null); return i.hasNext(); });
        t("AL forEach modifies via set", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); l.forEach(x -> l.set(0, 1)); return 1; });
        t("AL removeIf sorting", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); l.removeIf(x -> { l.add(1); return false; }); return 1; });
        t("AL replaceAll adding", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); l.replaceAll(x -> { l.add(1); return x; }); return 1; });
        t("AL sort adding", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); l.sort((x, y) -> { l.add(1); return 0; }); return 1; });
        t("AL toArray then iterate", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); Iterator<Integer> i = l.iterator(); l.toArray(); return i.next(); });
        t("AL stream add while", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); return l.stream().peek(x -> l.add(1)).count(); });
        t("AL stream add while map", () -> { ArrayList<Integer> l = new ArrayList<>(fresh()); return l.stream().map(x -> { l.add(1); return x; }).toList(); });
        t("LL replaceAll iterator", () -> { LinkedList<Integer> l = new LinkedList<>(fresh()); Iterator<Integer> i = l.iterator(); l.replaceAll(x -> x); return i.next(); });
        t("LL sort iterator", () -> { LinkedList<Integer> l = new LinkedList<>(fresh()); Iterator<Integer> i = l.iterator(); l.sort(null); return i.next(); });
        t("LL addAll empty iterator", () -> { LinkedList<Integer> l = new LinkedList<>(fresh()); Iterator<Integer> i = l.iterator(); l.addAll(List.of()); return i.next(); });
        t("LL addAll(1,empty) iterator", () -> { LinkedList<Integer> l = new LinkedList<>(fresh()); Iterator<Integer> i = l.iterator(); l.addAll(1, List.of()); return i.next(); });
        t("LL clear iterator", () -> { LinkedList<Integer> l = new LinkedList<>(); Iterator<Integer> i = l.iterator(); l.clear(); return i.hasNext(); });
        t("LL clear empty iterator next", () -> { LinkedList<Integer> l = new LinkedList<>(); Iterator<Integer> i = l.iterator(); l.clear(); return i.next(); });
        t("Vector ensure(100) iterator", () -> { Vector<Integer> l = new Vector<>(fresh()); Iterator<Integer> i = l.iterator(); l.ensureCapacity(100); return i.next(); });
        t("Vector trim iterator", () -> { Vector<Integer> l = new Vector<>(fresh()); Iterator<Integer> i = l.iterator(); l.trimToSize(); return i.next(); });
        t("Vector sort iterator", () -> { Vector<Integer> l = new Vector<>(fresh()); Iterator<Integer> i = l.iterator(); l.sort(null); return i.next(); });
        t("Vector replaceAll iterator", () -> { Vector<Integer> l = new Vector<>(fresh()); Iterator<Integer> i = l.iterator(); l.replaceAll(x -> x); return i.next(); });
        t("Vector addAll empty iterator", () -> { Vector<Integer> l = new Vector<>(fresh()); Iterator<Integer> i = l.iterator(); l.addAll(List.of()); return i.next(); });
        t("Vector removeAll none iterator", () -> { Vector<Integer> l = new Vector<>(fresh()); Iterator<Integer> i = l.iterator(); l.removeAll(List.of(9)); return i.next(); });
        t("Vector set iterator", () -> { Vector<Integer> l = new Vector<>(fresh()); Iterator<Integer> i = l.iterator(); l.set(0, 1); return i.next(); });
        t("Vector removeIf false iterator", () -> { Vector<Integer> l = new Vector<>(fresh()); Iterator<Integer> i = l.iterator(); l.removeIf(x -> false); return i.next(); });
        t("Vector removeIf true iterator", () -> { Vector<Integer> l = new Vector<>(fresh()); Iterator<Integer> i = l.iterator(); l.removeIf(x -> x == 1); return i.next(); });
    }
}
