import java.util.*;
import java.util.function.*;

public class Sub {
    static void t(String n, Supplier<Object> s) {
        try { System.out.println(n + " = " + s.get()); }
        catch (Throwable e) { System.out.println(n + " ! " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static List<Integer> fresh() { return new ArrayList<>(List.of(0, 1, 2, 3, 4, 5)); }
    // a class of the program under AbstractList, counting
    static class Mine extends AbstractList<Integer> {
        final ArrayList<Integer> in = new ArrayList<>(List.of(0, 1, 2, 3));
        public Integer get(int i) { return in.get(i); }
        public int size() { return in.size(); }
        public void add(int i, Integer e) { in.add(i, e); modCount++; }
        public Integer remove(int i) { modCount++; return in.remove(i); }
        public Integer set(int i, Integer e) { return in.set(i, e); }
        int mc() { return modCount; }
    }
    static class Quiet extends AbstractList<Integer> {
        final ArrayList<Integer> in = new ArrayList<>(List.of(0, 1, 2, 3));
        public Integer get(int i) { return in.get(i); }
        public int size() { return in.size(); }
        public void add(int i, Integer e) { in.add(i, e); }
        public Integer remove(int i) { return in.remove(i); }
    }
    public static void main(String[] a) {
        t("loop add 0", () -> { List<Integer> s = fresh().subList(1, 3); int n = 0; for (Integer x : s) { s.add(0, x); if (++n > 50) return "runaway"; } return n; });
        t("loop add 0 (Mine)", () -> { List<Integer> s = new Mine().subList(1, 3); int n = 0; for (Integer x : s) { s.add(0, x); if (++n > 50) return "runaway"; } return n; });
        t("loop add 0 (Quiet)", () -> { List<Integer> s = new Quiet().subList(1, 3); int n = 0; for (Integer x : s) { s.add(0, x); if (++n > 20) break; } return n + " " + s; });
        t("Mine itr add", () -> { Mine m = new Mine(); int n = 0; for (Integer x : m) { m.add(0, x); if (++n > 5) break; } return n; });
        t("Mine itr last remove", () -> { Mine m = new Mine(); int n = 0; for (Integer x : m) { if (x == 2) m.remove(3); n++; } return n; });
        t("Mine itr remove 2nd last", () -> { Mine m = new Mine(); int n = 0; for (Integer x : m) { if (x == 2) m.remove(0); n++; } return n; });
        t("Quiet itr add", () -> { Quiet m = new Quiet(); int n = 0; for (Integer x : m) { if (n == 1) m.add(0, 9); n++; if (n > 9) break; } return n; });
        t("Mine mc", () -> { Mine m = new Mine(); m.add(1, 5); m.remove(0); m.set(0, 3); m.add(7); m.clear(); return m.mc(); });
        t("Mine addAll", () -> { Mine m = new Mine(); m.addAll(List.of(1, 2, 3)); m.addAll(0, List.of(1, 2, 3)); return m.mc(); });
        t("Mine sub mc", () -> { Mine m = new Mine(); List<Integer> s = m.subList(1, 3); s.add(7); s.remove(0); return m.mc() + " " + s + " " + m; });
        t("Mine retain", () -> { Mine m = new Mine(); m.removeIf(x -> x > 1); return m.mc() + " " + m; });
        t("Mine iterator remove", () -> { Mine m = new Mine(); Iterator<Integer> i = m.iterator(); i.next(); i.remove(); i.next(); i.remove(); return m.mc() + " " + m; });
        t("Mine listIterator add", () -> { Mine m = new Mine(); ListIterator<Integer> i = m.listIterator(); i.next(); i.add(9); i.next(); i.set(8); return m.mc() + " " + m; });
        t("Mine itr then sub-change", () -> { Mine m = new Mine(); Iterator<Integer> i = m.iterator(); i.next(); m.subList(0, 2).add(5); return i.next(); });
        t("Mine itr remove then modcount", () -> { Mine m = new Mine(); Iterator<Integer> i = m.iterator(); i.next(); i.remove(); return i.next() + " " + m; });

        // sublist of ArrayList: change behind
        t("AL sub get after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.get(0); });
        t("AL sub size after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.size(); });
        t("AL sub set after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.set(0, 1); });
        t("AL sub set after list set", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.set(2, 9); return s + " " + s.set(0, 1); });
        t("AL sub add after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); s.add(1); return s; });
        t("AL sub remove after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.remove(0); });
        t("AL sub iterator after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.iterator(); });
        t("AL sub iterator next after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); Iterator<Integer> i = s.iterator(); l.add(9); return i.next(); });
        t("AL sub iterator hasNext after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); Iterator<Integer> i = s.iterator(); l.add(9); return i.hasNext(); });
        t("AL sub toString after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.toString(); });
        t("AL sub equals after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.equals(List.of(1, 2)); });
        t("AL sub hashCode after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.hashCode(); });
        t("AL sub indexOf after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.indexOf(2); });
        t("AL sub contains after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.contains(2); });
        t("AL sub clear after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); s.clear(); return s; });
        t("AL sub isEmpty after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.isEmpty(); });
        t("AL sub subList after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.subList(0, 1); });
        t("AL sub stream after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.stream().count(); });
        t("AL sub sort after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); s.sort(null); return s; });
        t("AL sub toArray after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.toArray().length; });
        t("AL sub forEach after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); s.forEach(x -> {}); return 1; });
        t("AL sub removeIf after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.removeIf(x -> false); });
        t("AL sub addAll after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.addAll(List.of()); });
        t("AL sub listIterator after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.listIterator(); });
        t("AL sub reversed after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.reversed(); });
        t("AL sub replaceAll after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); s.replaceAll(x -> x); return s; });
        t("AL sub get(9) after list add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(9); return s.get(9); });
        t("AL sub get after list remove ", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.remove(0); return s.get(0); });
        t("AL sub get after list sort", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.sort(null); return s.get(0); });
        t("AL sub get after list clear", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.clear(); return s.get(0); });
        t("AL sub get after list ensure", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); ((ArrayList<Integer>) l).ensureCapacity(100); return s.get(0); });
        t("AL sub get after list trim", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); ((ArrayList<Integer>) l).trimToSize(); return s.get(0); });
        t("AL sub get after list removeIf false", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.removeIf(x -> false); return s.get(0); });
        t("AL sub get after list removeIf true", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.removeIf(x -> x == 5); return s.get(0); });
        t("AL sub get after list addAll empty", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.addAll(List.of()); return s.get(0); });
        t("AL sub get after list removeAll none", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.removeAll(List.of(7)); return s.get(0); });
        t("AL sub get after list replaceAll", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.replaceAll(x -> x); return s.get(0); });
        t("AL sub get after list remove(Object) missing", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.remove((Object) 77); return s.get(0); });
        t("AL sub get after list reversed add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.reversed().add(1); return s.get(0); });
        t("AL sub add through, parent size", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); s.add(9); s.add(0, 8); s.remove(1); return l + " " + s; });
        t("AL sub of sub", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 5); List<Integer> u = s.subList(1, 3); u.add(9); return l + " " + s + " " + u; });
        t("AL sub of sub, then outer s used after u changed", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 5); List<Integer> u = s.subList(1, 3); u.add(9); return s.get(0) + " " + s.size(); });
        t("AL sub of sub, then u used after s changed", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 5); List<Integer> u = s.subList(1, 3); s.add(9); return u.get(0); });
        t("AL sub of sub, u after l changed", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 5); List<Integer> u = s.subList(1, 3); l.add(9); return u.get(0); });
        t("AL sub two siblings", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); List<Integer> u = l.subList(2, 4); s.add(9); return u.get(0); });
        t("AL sub iterator remove ok", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); Iterator<Integer> i = s.iterator(); i.next(); i.remove(); i.next(); i.remove(); return l + " " + s; });
        t("AL sub iterator, sub change", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); Iterator<Integer> i = s.iterator(); i.next(); s.add(9); return i.next(); });
        t("AL sub iterator, parent set", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); Iterator<Integer> i = s.iterator(); i.next(); l.set(2, 9); return i.next(); });
        t("AL sub iterator, last element then hasNext after add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); Iterator<Integer> i = s.iterator(); i.next(); i.next(); s.add(9); return i.hasNext() + " " + i.next(); });
        t("AL sub listIterator add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); ListIterator<Integer> i = s.listIterator(); i.next(); i.add(9); i.next(); i.set(8); return l + " " + s + " " + i.nextIndex(); });
        t("AL sub listIterator hasPrevious after add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); ListIterator<Integer> i = s.listIterator(); l.add(9); return i.hasPrevious() + " " + i.nextIndex() + " " + i.previousIndex(); });
        t("AL sub listIterator(5)", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); return s.listIterator(5); });
        t("AL sub listIterator(5) after add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(3); return s.listIterator(5); });
        t("AL sub add(5,..)", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); s.add(5, 3); return s; });
        t("AL sub add(5,..) after l add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(1); s.add(5, 3); return s; });
        t("AL sub remove(5) after l add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(1); return s.remove(5); });
        t("AL sub get(5) ", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); return s.get(5); });
        t("AL sub subList(0,9) after l add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(1); return s.subList(0, 9); });
        t("AL sub subList(2,1) after l add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); l.add(1); return s.subList(2, 1); });
        t("AL sub removeRange via clear parent", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 3); s.clear(); return l + " " + s; });
        t("AL sub retainAll", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); s.retainAll(List.of(2)); return l + " " + s; });
        t("AL sub removeAll then use", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); s.removeAll(List.of(2)); s.add(7); return l + " " + s; });
        t("AL sub addAll(i, c)", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); s.addAll(1, List.of(7, 8)); return l + " " + s; });
        t("AL sub removeIf", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); s.removeIf(x -> x == 2); return l + " " + s; });
        t("AL sub sort", () -> { List<Integer> l = new ArrayList<>(List.of(5, 4, 3, 2, 1)); List<Integer> s = l.subList(1, 4); s.sort(null); s.add(9); return l + " " + s; });
        t("AL sub replaceAll", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); s.replaceAll(x -> x * 10); s.add(9); return l + " " + s; });
        t("AL sub forEach adding", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); int[] c = {0}; s.forEach(x -> { if (c[0]++ < 20) s.add(x); }); return l; });
        t("AL sub forEach adding last", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); int[] n = {0}; s.forEach(x -> { if (n[0]++ == 2) s.add(9); }); return l + " " + n[0]; });
        t("AL sub stream", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); return s.stream().mapToInt(x -> x).sum(); });
        t("AL sub stream, l add while", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); return s.stream().peek(x -> l.add(1)).count(); });
        t("AL sub spliterator, l add", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); Spliterator<Integer> sp = s.spliterator(); l.add(1); return sp.estimateSize(); });
        t("AL sub spliterator, l add before tryAdvance", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); Spliterator<Integer> sp = s.spliterator(); l.add(1); return sp.tryAdvance(x -> {}); });
        t("AL sub spliterator, l add before forEachRemaining", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); Spliterator<Integer> sp = s.spliterator(); l.add(1); sp.forEachRemaining(x -> {}); return 1; });
        t("AL sub spliterator, l add after tryAdvance", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); Spliterator<Integer> sp = s.spliterator(); sp.tryAdvance(x -> {}); l.add(1); return sp.tryAdvance(x -> {}); });
        t("AL sub spliterator, l add after forEachRemaining", () -> { List<Integer> l = fresh(); List<Integer> s = l.subList(1, 4); Spliterator<Integer> sp = s.spliterator(); l.add(1); return sp.getExactSizeIfKnown(); });

        // LinkedList sublists
        t("LL sub get after l add", () -> { List<Integer> l = new LinkedList<>(fresh()); List<Integer> s = l.subList(1, 3); l.add(9); return s.get(0); });
        t("LL sub size after l add", () -> { List<Integer> l = new LinkedList<>(fresh()); List<Integer> s = l.subList(1, 3); l.add(9); return s.size(); });
        t("LL sub get after l set", () -> { List<Integer> l = new LinkedList<>(fresh()); List<Integer> s = l.subList(1, 3); l.set(1, 9); return s.get(0); });
        t("LL loop add", () -> { List<Integer> s = new LinkedList<>(fresh()).subList(1, 3); int n = 0; for (Integer x : s) { s.add(0, x); if (++n > 50) return "runaway"; } return n; });
        t("LL sub add through", () -> { List<Integer> l = new LinkedList<>(fresh()); List<Integer> s = l.subList(1, 3); s.add(9); s.add(0, 8); s.remove(1); return l + " " + s; });
        t("LL itr add", () -> { List<Integer> l = new LinkedList<>(fresh()); int n = 0; for (Integer x : l) { l.add(x); if (++n > 9) break; } return n; });
        // other lists: Vector, CopyOnWrite, Arrays.asList, List.of, unmodifiable, Collections.synchronizedList, nCopies
        t("Vector sub get after l add", () -> { List<Integer> l = new Vector<>(fresh()); List<Integer> s = l.subList(1, 3); l.add(9); return s.get(0); });
        t("Vector sub loop add", () -> { List<Integer> s = new Vector<>(fresh()).subList(1, 3); int n = 0; for (Integer x : s) { s.add(0, x); if (++n > 50) return "runaway"; } return n; });
        t("Vector itr add", () -> { List<Integer> l = new Vector<>(fresh()); int n = 0; for (Integer x : l) { l.add(x); if (++n > 9) break; } return n; });
        t("Stack sub get after l add", () -> { List<Integer> l = new Stack<>(); l.addAll(fresh()); List<Integer> s = l.subList(1, 3); l.add(9); return s.get(0); });
        t("COW sub get after l add", () -> { List<Integer> l = new java.util.concurrent.CopyOnWriteArrayList<>(fresh()); List<Integer> s = l.subList(1, 3); l.add(9); return s.get(0); });
        t("COW sub size after l add", () -> { List<Integer> l = new java.util.concurrent.CopyOnWriteArrayList<>(fresh()); List<Integer> s = l.subList(1, 3); l.add(9); return s.size(); });
        t("COW sub add through", () -> { List<Integer> l = new java.util.concurrent.CopyOnWriteArrayList<>(fresh()); List<Integer> s = l.subList(1, 3); s.add(9); return l + " " + s; });
        t("COW sub loop add", () -> { List<Integer> s = new java.util.concurrent.CopyOnWriteArrayList<>(fresh()).subList(1, 3); int n = 0; for (Integer x : s) { s.add(0, x); if (++n > 50) return "runaway"; } return n; });
        t("asList sub set", () -> { List<Integer> l = Arrays.asList(0, 1, 2, 3); List<Integer> s = l.subList(1, 3); s.set(0, 9); return l + " " + s; });
        t("asList sub add", () -> { List<Integer> l = Arrays.asList(0, 1, 2, 3); List<Integer> s = l.subList(1, 3); s.add(9); return l + " " + s; });
        t("asList sub iterator", () -> { List<Integer> l = Arrays.asList(0, 1, 2, 3); List<Integer> s = l.subList(1, 3); int n = 0; for (Integer x : s) { s.set(0, 9); n++; } return n + " " + l; });
        t("List.of sub get", () -> { List<Integer> s = List.of(0, 1, 2, 3).subList(1, 3); return s.get(0); });
        t("unmod sub get after l add", () -> { List<Integer> l = fresh(); List<Integer> s = Collections.unmodifiableList(l).subList(1, 3); l.add(9); return s.get(0); });
        t("unmod sub size after l add", () -> { List<Integer> l = fresh(); List<Integer> s = Collections.unmodifiableList(l).subList(1, 3); l.add(9); return s.size(); });
        t("unmod sub add", () -> { List<Integer> l = fresh(); List<Integer> s = Collections.unmodifiableList(l).subList(1, 3); s.add(9); return s; });
        t("sync sub get after l add", () -> { List<Integer> l = fresh(); List<Integer> s = Collections.synchronizedList(l).subList(1, 3); l.add(9); return s.get(0); });
        t("sync sub add", () -> { List<Integer> l = fresh(); List<Integer> s = Collections.synchronizedList(l).subList(1, 3); s.add(9); return l; });
        t("nCopies sub", () -> { List<Integer> s = Collections.nCopies(5, 1).subList(1, 3); return s; });
        t("reversed sub get after l add", () -> { List<Integer> l = fresh(); List<Integer> s = l.reversed().subList(1, 3); l.add(9); return s.get(0); });
        t("reversed sub add", () -> { List<Integer> l = fresh(); List<Integer> s = l.reversed().subList(1, 3); s.add(9); return l + " " + s; });
        t("LL reversed sub", () -> { List<Integer> l = new LinkedList<>(fresh()); List<Integer> s = l.reversed().subList(1, 3); l.add(9); return s.get(0); });
        t("emptyList sub", () -> Collections.emptyList().subList(0, 0));
        t("singletonList sub", () -> Collections.singletonList(1).subList(0, 1));
        t("sub of ArrayList of ArrayList equals", () -> fresh().subList(1, 3).equals(new ArrayList<>(List.of(1, 2))));
        t("sub class", () -> fresh().subList(1, 3).getClass().getName());
        t("sub of sub class", () -> fresh().subList(1, 3).subList(0, 1).getClass().getName());
        t("LL sub class", () -> new LinkedList<>(fresh()).subList(1, 3).getClass().getName());
        t("Mine sub class", () -> new Mine().subList(1, 3).getClass().getName());
        t("Mine sub RandomAccess", () -> new Mine().subList(1, 3) instanceof RandomAccess);
        t("Mine sub get after list add", () -> { Mine l = new Mine(); List<Integer> s = l.subList(1, 3); l.add(9); return s.get(0); });
        t("Mine sub size after list add", () -> { Mine l = new Mine(); List<Integer> s = l.subList(1, 3); l.add(9); return s.size(); });
        t("Mine sub through add then mine mc", () -> { Mine l = new Mine(); List<Integer> s = l.subList(1, 3); s.add(9); s.add(0, 8); return l.mc() + " " + s.size(); });
        t("Quiet sub get after list add", () -> { Quiet l = new Quiet(); List<Integer> s = l.subList(1, 3); l.add(9); return s.get(0) + " " + s.size(); });
        t("Quiet sub through", () -> { Quiet l = new Quiet(); List<Integer> s = l.subList(1, 3); s.add(9); return s + " " + l; });
    }
}
