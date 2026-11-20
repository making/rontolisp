import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class Imm2 {
    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getName() + " " + e.getMessage());
        }
    }

    static String split(Spliterator<?> s) {
        StringBuilder out = new StringBuilder(s.characteristics() + " " + s.estimateSize() + " " + s.getExactSizeIfKnown());
        s.tryAdvance(x -> out.append(" first=").append(x));
        out.append(" then ").append(s.estimateSize());
        out.append(" split=").append(s.trySplit());
        return out.toString();
    }

    public static void main(String[] args) {
        Iterator<Integer> ei = Collections.emptyIterator();
        attempt("emptyIterator", () -> ei.hasNext() + " " + ei.getClass().getName() + " " + ((Object) ei == Collections.emptyIterator()));
        attempt("emptyIterator next", () -> ei.next());
        attempt("emptyIterator remove", () -> { ei.remove(); return "ok"; });
        attempt("emptyIterator forEachRemaining", () -> { ei.forEachRemaining(x -> {}); return "ok"; });
        attempt("emptyIterator forEachRemaining(null)", () -> { ei.forEachRemaining(null); return "ok"; });
        ListIterator<Integer> eli = Collections.emptyListIterator();
        attempt("emptyListIterator", () -> eli.hasNext() + " " + eli.hasPrevious() + " " + eli.nextIndex() + " " + eli.previousIndex() + " " + eli.getClass().getName() + " " + ((Object) eli == Collections.emptyListIterator()));
        attempt("emptyListIterator next", () -> eli.next());
        attempt("emptyListIterator previous", () -> eli.previous());
        attempt("emptyListIterator remove", () -> { eli.remove(); return "ok"; });
        attempt("emptyListIterator set", () -> { eli.set(1); return "ok"; });
        attempt("emptyListIterator add", () -> { eli.add(1); return "ok"; });
        attempt("emptyListIterator forEachRemaining(null)", () -> { eli.forEachRemaining(null); return "ok"; });
        Enumeration<Integer> ee = Collections.emptyEnumeration();
        attempt("emptyEnumeration", () -> ee.hasMoreElements() + " " + ee.getClass().getName() + " " + ((Object) ee == Collections.emptyEnumeration()));
        attempt("emptyEnumeration next", () -> ee.nextElement());
        attempt("emptyEnumeration asIterator", () -> ee.asIterator().hasNext() + " " + ee.asIterator().getClass().getName());
        attempt("emptyList iterator", () -> Collections.emptyList().iterator().getClass().getName() + " " + Collections.emptyList().listIterator().getClass().getName() + " " + Collections.emptyList().listIterator(0).getClass().getName());
        attempt("emptySet iterator", () -> Collections.emptySet().iterator().getClass().getName());
        attempt("singletonList iterator", () -> Collections.singletonList(1).iterator().getClass().getName());
        attempt("singleton iterator", () -> Collections.singleton(1).iterator().getClass().getName());
        attempt("nCopies iterator", () -> Collections.nCopies(2, 1).iterator().getClass().getName());
        Map<Integer, String> sm = Collections.singletonMap(1, "a");
        attempt("singletonMap views", () -> sm.keySet().getClass().getName() + " " + sm.values().getClass().getName() + " " + sm.entrySet().getClass().getName() + " " + (sm.values() instanceof Set));
        attempt("singletonMap forEach", () -> { StringBuilder b = new StringBuilder(); sm.forEach((k, v) -> b.append(k).append(v)); return b; });
        attempt("singletonMap forEach(null)", () -> { sm.forEach(null); return "ok"; });
        attempt("singletonMap equals", () -> sm.equals(Map.of(1, "a")) + " " + Map.of(1, "a").equals(sm) + " " + sm.hashCode() + " " + sm.toString());
        attempt("singletonMap null", () -> { Map<Integer, String> n = Collections.singletonMap(null, null); return n + " " + n.get(null) + " " + n.containsKey(null) + " " + n.containsValue(null) + " " + n.hashCode() + " " + n.getOrDefault(null, "d") + " " + n.getOrDefault(2, "d"); });
        attempt("singletonMap entry", () -> { Map.Entry<Integer, String> e = sm.entrySet().iterator().next(); return e + " " + e.getKey() + " " + e.getValue() + " " + e.hashCode() + " " + e.equals(Map.entry(1, "a")); });
        attempt("singletonMap values remove a", () -> sm.values().remove("a"));
        attempt("singletonMap keySet remove 1", () -> sm.keySet().remove(1));
        attempt("singletonMap entrySet remove", () -> sm.entrySet().remove(Map.entry(1, "a")));
        attempt("singletonMap entrySet remove other", () -> sm.entrySet().remove(Map.entry(2, "a")));
        attempt("singletonMap spliterators", () -> sm.keySet().spliterator().characteristics() + " " + sm.values().spliterator().characteristics() + " " + sm.entrySet().spliterator().characteristics());
        attempt("emptyMap views", () -> { Map<Integer, String> m = Collections.emptyMap(); return m.keySet().getClass().getName() + " " + m.values().getClass().getName() + " " + m.entrySet().getClass().getName() + " " + ((Object) m.keySet() == Collections.emptySet()) + " " + ((Object) m.values() == Collections.emptySet()) + " " + ((Object) m.values() == Collections.emptyList()); });
        attempt("emptyMap forEach(null)", () -> { Collections.emptyMap().forEach(null); return "ok"; });
        attempt("emptyMap equals", () -> Collections.emptyMap().equals(new HashMap<>()) + " " + Collections.emptyMap().hashCode() + " " + Collections.emptyMap().toString() + " " + Collections.emptyMap().getOrDefault(1, "d"));
        attempt("singletonList(null)", () -> { List<Integer> l = Collections.singletonList(null); return l + " " + l.contains(null) + " " + l.indexOf(null) + " " + l.hashCode() + " " + split(l.spliterator()); });
        attempt("singleton(null)", () -> { Set<Integer> l = Collections.singleton(null); return l + " " + l.contains(null) + " " + l.hashCode() + " " + split(l.spliterator()); });
        attempt("singletonList spliterator", () -> split(Collections.singletonList(5).spliterator()));
        attempt("singleton spliterator", () -> split(Collections.singleton(5).spliterator()));
        attempt("emptyList spliterator", () -> split(Collections.emptyList().spliterator()));
        attempt("emptySet spliterator", () -> split(Collections.emptySet().spliterator()));
        for (int n = 0; n < 4; n++) {
            int k = n;
            attempt("nCopies(" + n + ") spliterator", () -> split(Collections.nCopies(k, "x").spliterator()));
            attempt("nCopies(" + n + ") stream", () -> Collections.nCopies(k, "x").stream().collect(Collectors.joining(",")) + " " + Collections.nCopies(k, "x").stream().spliterator().characteristics());
            attempt("nCopies(" + n + ", null)", () -> { List<String> l = Collections.nCopies(k, null); return l + " " + l.contains(null) + " " + l.indexOf(null) + " " + l.lastIndexOf(null) + " " + l.hashCode() + " " + split(l.spliterator()); });
        }
        attempt("nCopies equals", () -> Collections.nCopies(3, "x").equals(Collections.nCopies(3, "x")) + " " + Collections.nCopies(3, "x").equals(Collections.nCopies(2, "x")) + " " + Collections.nCopies(2, "x").equals(Arrays.asList("x", "x")) + " " + Collections.nCopies(2, "x").equals(Arrays.asList("x", "y")));
        attempt("nCopies subList", () -> { List<String> s = Collections.nCopies(5, "x").subList(1, 3); return s + " " + s.getClass().getName() + " " + s.subList(0, 1).getClass().getName(); });
        attempt("nCopies subList(1,7)", () -> Collections.nCopies(5, "x").subList(1, 7));
        attempt("nCopies subList(-1,2)", () -> Collections.nCopies(5, "x").subList(-1, 2));
        attempt("nCopies subList(3,2)", () -> Collections.nCopies(5, "x").subList(3, 2));
        attempt("nCopies toArray(arr)", () -> { String[] a = {"a", "b", "c", "d"}; Collections.nCopies(2, "x").toArray(a); return Arrays.toString(a); });
        attempt("emptyList toArray(arr)", () -> { String[] a = {"a", "b"}; Collections.<String>emptyList().toArray(a); return Arrays.toString(a); });
        attempt("singletonList toArray(arr)", () -> { String[] a = {"a", "b", "c"}; Collections.singletonList("x").toArray(a); return Arrays.toString(a); });
        attempt("nCopies(big)", () -> Collections.nCopies(2000000000, "x").size() + " " + Collections.nCopies(2000000000, "x").get(1999999999) + " " + Collections.nCopies(2000000000, "x").lastIndexOf("x"));
        attempt("emptyList equals", () -> Collections.emptyList().equals(new ArrayList<>()) + " " + Collections.emptyList().equals(Set.of()) + " " + Collections.emptySet().equals(Set.of()) + " " + Collections.emptySet().equals(List.of()));
        attempt("emptyList containsAll", () -> Collections.emptyList().containsAll(List.of()) + " " + Collections.emptyList().containsAll(List.of(1)) + " " + Collections.emptySet().containsAll(List.of(1)));
        attempt("emptyList get(0)", () -> Collections.emptyList().get(0));
        attempt("singleton toString", () -> Collections.singleton("x") + " " + Collections.singletonList("y") + " " + Collections.nCopies(2, "z") + " " + Collections.emptySet() + " " + Collections.singletonMap("k", "v"));
        attempt("List.of(1,2,3) removeFirst", () -> List.of(1, 2, 3).removeFirst());
        attempt("List.of(1,2,3) subList clear", () -> { List<Integer> l = List.of(1, 2, 3); l.subList(1, 1).clear(); return l; });
        attempt("List.of(1,2,3) subList class", () -> List.of(1, 2, 3).subList(1, 2).getClass().getName() + " " + (List.of(1, 2, 3).subList(1, 2) instanceof RandomAccess));
        attempt("List.of(1,2,3) subList get(-1)", () -> List.of(1, 2, 3).subList(1, 3).get(-1));
        attempt("List.of(1,2,3) subList get(2)", () -> List.of(1, 2, 3).subList(1, 3).get(2));
        attempt("List.of(1,2,3) subList contains(null)", () -> List.of(1, 2, 3).subList(1, 3).contains(null));
        attempt("List.of(1,2,3) subList removeIf", () -> List.of(1, 2, 3).subList(1, 3).removeIf(x -> false));
        attempt("List.of(1,2,3) subList reversed", () -> List.of(1, 2, 3).subList(0, 3).reversed() + " " + List.of(1, 2, 3).subList(0, 3).reversed().getClass().getName());
        attempt("List.of(1,2,3) subList equals", () -> List.of(1, 2, 3).subList(1, 3).equals(List.of(2, 3)) + " " + List.of(1, 2, 3).subList(1, 3).hashCode());
        attempt("List.of(1,2,3) subList subList", () -> List.of(1, 2, 3, 4).subList(1, 4).subList(1, 2) + " " + List.of(1, 2, 3, 4).subList(1, 4).subList(1, 5));
        attempt("List.of(1,2,3) subList(0,4)", () -> List.of(1, 2, 3).subList(0, 4));
        attempt("List.of(1,2,3) subList(2,1)", () -> List.of(1, 2, 3).subList(2, 1));
        attempt("List.of(1,2,3) get(5)", () -> List.of(1, 2, 3).get(5));
        attempt("List.of(1,2,3,4,5) get(-1)", () -> List.of(1, 2, 3, 4, 5).get(-1));
        attempt("List.copyOf(2) get(2)", () -> List.copyOf(List.of(1, 2)).get(2));
        attempt("List.copyOf(3) get(3)", () -> List.copyOf(new ArrayList<>(List.of(1, 2, 3))).get(3));
        attempt("List.of iterator", () -> { Iterator<Integer> it = List.of(1, 2).iterator(); it.next(); it.remove(); return "ok"; });
        attempt("List.of listIterator", () -> { ListIterator<Integer> it = List.of(1, 2).listIterator(); it.next(); it.set(3); return "ok"; });
        attempt("Set.of(1,2) contains(null)", () -> Set.of(1, 2).contains(null));
        attempt("Set.of(1,2,3) contains(null)", () -> Set.of(1, 2, 3).contains(null));
        attempt("Set.of(1,2,3) containsAll(null el)", () -> Set.of(1, 2, 3).containsAll(Arrays.asList(1, null)));
        attempt("Set.copyOf contains(null)", () -> Set.copyOf(List.of(1)).contains(null));
        attempt("Set.of addAll()", () -> Set.of(1).addAll(List.of()));
        attempt("Map.of(1,a,2,b) get(null)", () -> Map.of(1, "a", 2, "b").get(null));
        attempt("Map.of(1,a,2,b) getOrDefault(null)", () -> Map.of(1, "a", 2, "b").getOrDefault(null, "d"));
        attempt("Map.of(1,a,2,b) containsValue(null)", () -> Map.of(1, "a", 2, "b").containsValue(null));
        attempt("Map.ofEntries get(null)", () -> Map.ofEntries(Map.entry(1, "a")).get(null));
        attempt("Map.copyOf get(null)", () -> Map.copyOf(new HashMap<>(Map.of(1, "a"))).get(null));
        attempt("Map.of keySet contains(null)", () -> Map.of(1, "a").keySet().contains(null));
        attempt("Map.of values contains(null)", () -> Map.of(1, "a").values().contains(null));
        attempt("Map.of entrySet contains(null)", () -> Map.of(1, "a").entrySet().contains(null));
        attempt("Map.of keySet remove(1)", () -> Map.of(1, "a").keySet().remove(1));
        attempt("Map.of keySet removeIf(false)", () -> Map.of(1, "a").keySet().removeIf(x -> false));
        attempt("Map.of keySet addAll()", () -> Map.of(1, "a").keySet().addAll(List.of()));
        attempt("Map.of(1,a,2,b) keySet remove(3)", () -> Map.of(1, "a", 2, "b").keySet().remove(3));
        attempt("Map.of(1,a,2,b) keySet removeAll()", () -> Map.of(1, "a", 2, "b").keySet().removeAll(List.of()));
        attempt("Map.of(1,a,2,b) values removeIf", () -> Map.of(1, "a", 2, "b").values().removeIf(x -> false));
        attempt("Map.of(1,a,2,b) entrySet clear", () -> { Map.of(1, "a", 2, "b").entrySet().clear(); return "ok"; });
        attempt("unmodifiableMap get(null)", () -> Collections.unmodifiableMap(new HashMap<>(Map.of(1, "a"))).get(null));
        attempt("unmodifiableSet contains(null)", () -> Collections.unmodifiableSet(new HashSet<>(Set.of(1))).contains(null));
        attempt("unmodifiableCollection addAll()", () -> Collections.unmodifiableCollection(new ArrayList<>(List.of(1))).addAll(List.of()));
        attempt("unmodifiableList addAll(0, ())", () -> Collections.unmodifiableList(new ArrayList<>(List.of(1))).addAll(0, List.of()));
        attempt("unmodifiableList subList clear", () -> { List<Integer> l = Collections.unmodifiableList(new ArrayList<>(List.of(1))); l.subList(0, 0).clear(); return l; });
        attempt("unmodifiableMap putAll()", () -> { Collections.unmodifiableMap(new HashMap<>(Map.of(1, "a"))).putAll(Map.of()); return "ok"; });
        attempt("Map.of putAll()", () -> { Map.of(1, "a").putAll(Map.of()); return "ok"; });
        attempt("asList addAll(9, ())", () -> Arrays.asList(1, 2).addAll(9, List.of()));
        attempt("asList iterator class", () -> Arrays.asList(1, 2).iterator().getClass().getName() + " " + Arrays.asList(1, 2).listIterator().getClass().getName());
        attempt("asList iterator", () -> { Iterator<Integer> it = Arrays.asList(1, 2).iterator(); return it.next() + " " + it.next() + " " + it.hasNext(); });
        attempt("asList iterator past", () -> { Iterator<Integer> it = Arrays.asList(1).iterator(); it.next(); return it.next(); });
        attempt("asList forEachRemaining", () -> { StringBuilder b = new StringBuilder(); Iterator<Integer> it = Arrays.asList(1, 2, 3).iterator(); it.next(); it.forEachRemaining(b::append); return b; });
        attempt("asList listIterator remove", () -> { ListIterator<Integer> it = Arrays.asList(1, 2).listIterator(); it.next(); it.remove(); return "ok"; });
        attempt("asList listIterator set", () -> { List<Integer> l = Arrays.asList(1, 2); ListIterator<Integer> it = l.listIterator(); it.next(); it.set(5); return l; });
    }
}
