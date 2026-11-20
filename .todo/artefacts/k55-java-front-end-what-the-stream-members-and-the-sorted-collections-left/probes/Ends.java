import java.util.*;
import java.util.function.*;
import java.util.stream.*;

class EndsOwn<E> extends AbstractCollection<E> implements SequencedCollection<E> {
    final List<E> held = new ArrayList<>();

    public Iterator<E> iterator() {
        return held.iterator();
    }

    public int size() {
        return held.size();
    }

    public boolean add(E e) {
        return held.add(e);
    }

    public SequencedCollection<E> reversed() {
        return held.reversed();
    }
}

class EndsOwnMap<K, V> extends AbstractMap<K, V> implements SequencedMap<K, V> {
    final LinkedHashMap<K, V> held = new LinkedHashMap<>();

    public Set<Map.Entry<K, V>> entrySet() {
        return held.entrySet();
    }

    public V put(K key, V value) {
        return held.put(key, value);
    }

    public SequencedMap<K, V> reversed() {
        return held.reversed();
    }
}

class Ends {
    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        // a list's reversed view: an index from the other end, through the list's members
        List<Integer> l = new ArrayList<>(List.of(1, 2, 3, 4, 2, 5));
        List<Integer> r = l.reversed();
        System.out.println(r + " " + r.get(0) + " " + r.indexOf(2) + " " + r.lastIndexOf(2) + " " + r.set(0, 7) + " " + r.add(8) + " " + l + " " + (r.reversed() == l) + " " + (r instanceof RandomAccess) + " " + (new LinkedList<>(l).reversed() instanceof RandomAccess));
        r.add(1, 9);
        r.addFirst(10);
        r.addLast(11);
        System.out.println(l + " " + r.remove(0) + " " + r.remove(Integer.valueOf(2)) + " " + r.removeFirst() + " " + r.removeLast() + " " + r.getFirst() + " " + r.getLast() + " " + l);
        r.addAll(List.of(20, 21));
        r.addAll(1, List.of(30, 31));
        System.out.println(l + " " + r.subList(1, 3) + " " + r.subList(1, 3).reversed());
        ListIterator<Integer> each = r.listIterator(2);
        System.out.print(each.nextIndex() + " " + each.previousIndex() + " " + each.next() + " " + each.previous() + " " + each.previous());
        each = r.listIterator();
        each.next();
        each.set(50);
        each.add(51);
        each.next();
        each.remove();
        System.out.print(" " + l);
        r.sort(null);
        System.out.println(" " + l + " " + r.stream().map(x -> x * 2).toList() + " " + r.equals(new ArrayList<>(r)) + " " + (r.hashCode() == new ArrayList<>(r).hashCode()));
        attempt("get(-1)", () -> r.get(-1));
        attempt("add(size + 1)", () -> {
            r.add(r.size() + 1, 0);
            return r;
        });
        attempt("subList(2, 1)", () -> r.subList(2, 1));
        attempt("list subList(2, 1)", () -> l.subList(2, 1));
        attempt("list subList(-1, 1)", () -> l.subList(-1, 1));
        attempt("list subList(0, 99)", () -> new LinkedList<>(l).subList(0, 99));
        attempt("walk while adding", () -> {
            for (Integer x : r) {
                l.add(x);
            }
            return l;
        });
        attempt("List.of", () -> List.of(1, 2, 3).reversed() + " " + List.of(1, 2, 3).reversed().get(0) + " " + List.of(1, 2, 3).reversed().getLast());
        attempt("List.of set", () -> List.of(1, 2, 3).reversed().set(5, 0));
        attempt("List.of remove", () -> List.of(1, 2, 3).reversed().remove(Integer.valueOf(9)));
        attempt("unmodifiableList set", () -> Collections.unmodifiableList(l).reversed().set(99, 0));
        attempt("unmodifiableList removeIf", () -> Collections.unmodifiableList(l).removeIf(x -> false));
        // a LinkedList's reversed view is a LinkedList, a deque's a deque
        LinkedList<Integer> ll = new LinkedList<>(List.of(1, 2, 3));
        LinkedList<Integer> lr = ll.reversed();
        lr.push(9);
        lr.offer(8);
        System.out.println(lr + " " + ll + " " + lr.pop() + " " + lr.pollLast() + " " + lr.peekFirst() + " " + lr.get(1) + " " + (lr.reversed() == ll) + " " + lr.equals(List.of(3, 2, 1)) + " " + lr.hashCode());
        Deque<String> d = new ArrayDeque<>(List.of("a", "b", "c"));
        Deque<String> dr = d.reversed();
        dr.push("x");
        dr.offer("y");
        dr.addFirst("z");
        System.out.println(d + " " + dr + " " + dr.peek() + " " + dr.pollLast() + " " + dr.pop() + " " + dr.remove("a") + " " + d + " " + (dr.reversed() == d) + " " + dr.contains("b") + " " + dr.removeLastOccurrence("b") + " " + d);
        java.util.concurrent.LinkedBlockingDeque<Integer> bounded = new java.util.concurrent.LinkedBlockingDeque<>(2);
        Deque<Integer> br = bounded.reversed();
        System.out.println(br.offer(1) + " " + br.offer(2) + " " + br.offer(3) + " " + bounded);
        attempt("full", () -> br.add(4));
        java.util.concurrent.ConcurrentLinkedDeque<Integer> cd = new java.util.concurrent.ConcurrentLinkedDeque<>(List.of(1, 2, 3, 2, 4));
        Iterator<Integer> taken = cd.reversed().iterator();
        taken.next();
        taken.next();
        taken.remove();
        System.out.print(cd + " " + cd.reversed().removeIf(x -> x == 3) + " " + cd);
        Queue<Integer> q = new java.util.concurrent.LinkedBlockingQueue<>(List.of(1, 2, 3));
        java.util.concurrent.CopyOnWriteArrayList<Integer> cow = new java.util.concurrent.CopyOnWriteArrayList<>(List.of(1, 2, 3, 2));
        System.out.println(" " + q.retainAll(List.of(2)) + " " + q + " " + cow.removeAll(List.of(2)) + " " + cow + " " + cow.retainAll(List.of(3)) + " " + cow);
        // a linked set puts an element at an end, moving it if it is there
        LinkedHashSet<String> s = new LinkedHashSet<>(List.of("a", "b", "c", "d"));
        SequencedSet<String> sr = s.reversed();
        s.addFirst("c");
        s.addLast("a");
        s.addFirst("n");
        sr.add("x");
        sr.addFirst("y");
        System.out.println(s + " " + sr + " " + s.getFirst() + " " + s.getLast() + " " + sr.getFirst() + " " + s.removeFirst() + " " + sr.removeFirst() + " " + s + " " + (sr.reversed() == s) + " " + sr.equals(s) + " " + LinkedHashSet.newLinkedHashSet(4));
        attempt("walk while moving", () -> {
            for (String x : s) {
                s.addLast(s.getFirst());
            }
            return s;
        });
        attempt("walk while putting the first first", () -> {
            for (String x : s) {
                s.addFirst(s.getFirst());
            }
            return s;
        });
        attempt("empty", () -> new LinkedHashSet<String>().reversed().getFirst());
        // a linked map puts a key at an end; an entry it answers is a copy
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        for (String k : "a b c d".split(" ")) {
            m.put(k, k.charAt(0) - 'a' + 1);
        }
        SequencedMap<String, Integer> mr = m.reversed();
        System.out.println(m.putFirst("c", 30) + " " + m.putLast("a", 10) + " " + m.putFirst("n", 40) + " " + mr.put("x", 24) + " " + mr.putFirst("y", 25) + " " + mr.putLast("z", 26) + " " + m + " " + mr);
        System.out.println(m.firstEntry() + " " + m.lastEntry() + " " + mr.firstEntry() + " " + m.pollFirstEntry() + " " + mr.pollFirstEntry() + " " + m + " " + m.sequencedKeySet() + " " + m.sequencedValues().reversed() + " " + m.sequencedEntrySet().getLast() + " " + mr.keySet() + " " + mr.values() + " " + mr.entrySet() + " " + mr.sequencedKeySet().reversed());
        attempt("firstEntry setValue", () -> m.firstEntry().setValue(0));
        attempt("an entry of the set", () -> m.sequencedEntrySet().getFirst().setValue(0) + " " + m);
        attempt("an entry of the view's set", () -> mr.sequencedEntrySet().getFirst().setValue(0));
        attempt("keys add", () -> m.sequencedKeySet().add("q"));
        attempt("walk while putting the last last", () -> {
            for (String k : m.keySet()) {
                m.putLast(m.lastEntry().getKey(), 1);
            }
            return m;
        });
        attempt("walk while moving", () -> {
            for (String k : m.keySet()) {
                m.putLast(k, 2);
            }
            return m;
        });
        StringBuilder seen = new StringBuilder();
        mr.replaceAll((k, v) -> {
            seen.append(k);
            return v + 1;
        });
        mr.forEach((k, v) -> seen.append(v));
        System.out.println(seen + " " + mr.equals(m) + " " + (mr.hashCode() == m.hashCode()) + " " + (mr.reversed() == m) + " " + mr.containsKey("c") + " " + mr.remove("c") + " " + m);
        StringBuilder eldest = new StringBuilder();
        LinkedHashMap<String, Integer> lru = new LinkedHashMap<>(16, 0.75f, true) {
            protected boolean removeEldestEntry(Map.Entry<String, Integer> e) {
                eldest.append(e.getKey());
                return size() > 3;
            }
        };
        lru.put("a", 1);
        lru.put("b", 2);
        lru.put("c", 3);
        lru.putFirst("d", 4);
        System.out.print(lru + " " + eldest);
        lru.putLast("e", 5);
        lru.putFirst("c", 30);
        System.out.print(" " + lru + " " + eldest);
        lru.reversed().get("b");
        lru.reversed().putFirst("f", 6);
        System.out.println(" " + lru + " " + eldest + " " + lru.firstEntry() + " " + lru);
        // the defaults over a program's own sequenced collection and map
        EndsOwn<String> own = new EndsOwn<>();
        attempt("own getFirst", () -> own.getFirst());
        own.add("u");
        own.add("v");
        own.add("w");
        System.out.println(own.getFirst() + " " + own.getLast() + " " + own.removeFirst() + " " + own.removeLast() + " " + own);
        attempt("own addFirst", () -> {
            own.addFirst("t");
            return own;
        });
        EndsOwnMap<String, Integer> om = new EndsOwnMap<>();
        om.put("p", 1);
        om.put("q", 2);
        System.out.println(om.firstEntry() + " " + om.lastEntry() + " " + om.sequencedKeySet() + " " + om.sequencedKeySet().getLast() + " " + om.sequencedValues().reversed() + " " + om.pollFirstEntry() + " " + om);
        attempt("own firstEntry setValue", () -> om.firstEntry().setValue(5));
        attempt("own putFirst", () -> om.putFirst("z", 0));
        attempt("own keys removeFirst", () -> om.sequencedKeySet().removeFirst() + " " + om);
        attempt("own keys getFirst", () -> om.sequencedKeySet().getFirst());
        // a sorted set or map that is not navigable: a view walking it back
        SortedSet<Integer> sorted = Collections.unmodifiableSortedSet(new TreeSet<>(List.of(5, 1, 4, 2, 3)));
        SortedSet<Integer> pr = sorted.reversed();
        System.out.println(pr + " " + pr.first() + " " + pr.last() + " " + pr.headSet(3) + " " + pr.tailSet(3) + " " + pr.subSet(4, 2) + " " + pr.subSet(2, 4) + " " + pr.headSet(3).tailSet(4) + " " + pr.comparator().compare(1, 2) + " " + (pr.reversed() == sorted) + " " + pr.equals(sorted) + " " + pr.subSet(4, 2).reversed() + " " + pr.subSet(4, 2).size());
        attempt("headSet(3).headSet(1)", () -> pr.headSet(3).headSet(1));
        attempt("headSet(3).add(1)", () -> pr.headSet(3).add(1));
        attempt("add", () -> pr.add(6));
        SortedSet<Integer> grown = Collections.synchronizedSortedSet(new TreeSet<>(List.of(1, 2, 3)));
        Iterator<Integer> walk = grown.reversed().iterator();
        System.out.print(walk.next());
        grown.add(0);
        grown.add(9);
        while (walk.hasNext()) {
            System.out.print(walk.next());
        }
        SortedMap<String, Integer> pm = Collections.synchronizedSortedMap(new TreeMap<>(Map.of("a", 1, "b", 2, "c", 3, "d", 4)));
        SortedMap<String, Integer> pmr = pm.reversed();
        System.out.println(" " + pmr + " " + pmr.firstKey() + " " + pmr.headMap("b") + " " + pmr.tailMap("b") + " " + pmr.subMap("c", "a") + " " + pmr.headMap("b").tailMap("c") + " " + pmr.firstEntry() + " " + pmr.put("e", 5) + " " + pmr.headMap("c").remove("a") + " " + pmr.pollFirstEntry() + " " + pm);
        attempt("subMap(a, c)", () -> pmr.subMap("a", "c"));
        attempt("headMap(c).put(b)", () -> pmr.headMap("c").put("b", 0));
        attempt("firstEntry setValue", () -> pm.firstEntry().setValue(0));
        attempt("an entry of the view", () -> pmr.entrySet().iterator().next().setValue(40) + " " + pm);
        attempt("reverseOrder(null)", () -> Collections.reverseOrder(null).compare(1, 2));
        // what Collections makes of them
        SequencedSet<String> fromMap = Collections.newSequencedSetFromMap(new LinkedHashMap<>());
        fromMap.add("b");
        fromMap.add("a");
        fromMap.addFirst("z");
        System.out.println(fromMap + " " + fromMap.reversed() + " " + fromMap.removeLast() + " " + fromMap + " " + Collections.unmodifiableSequencedSet(s).reversed() + " " + Collections.unmodifiableSequencedMap(m).reversed() + " " + Collections.unmodifiableSequencedCollection(d).getLast() + " " + Collections.unmodifiableSequencedMap(m).firstEntry());
        attempt("newSetFromMap", () -> Collections.newSetFromMap(new HashMap<>(Map.of("a", true))));
        attempt("unmodifiable removeFirst", () -> Collections.unmodifiableSequencedCollection(d).removeFirst());
        attempt("unmodifiable compute", () -> Collections.unmodifiableMap(m).compute("q", (k, v) -> null));
        attempt("unmodifiable equals", () -> Collections.unmodifiableCollection(l).equals(l) + " " + Collections.unmodifiableSequencedCollection(l).equals(l) + " " + Collections.unmodifiableSequencedSet(s).equals(s) + " " + Map.of("a", 1).entrySet().equals(Set.of(Map.entry("a", 1))) + " " + Collections.unmodifiableMap(m).entrySet().equals(m.entrySet()));
        // a table for so many mappings
        for (int n : new int[] {12, 13, 24, 25}) {
            Map<Integer, Integer> h = HashMap.newHashMap(n);
            Set<Integer> hs = HashSet.newHashSet(n);
            for (int i = 0; i < n; i++) {
                h.put(i * 17, i);
                hs.add(i * 17);
            }
            System.out.println(h.keySet() + " " + hs.toString().equals(h.keySet().toString()) + " " + LinkedHashMap.newLinkedHashMap(n).isEmpty());
        }
        attempt("newHashMap(-1)", () -> HashMap.newHashMap(-1));
        attempt("newLinkedHashSet(-1)", () -> LinkedHashSet.newLinkedHashSet(-1));
    }
}
