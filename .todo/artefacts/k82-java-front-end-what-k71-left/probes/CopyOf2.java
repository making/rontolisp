import java.util.*;
import java.util.stream.*;

public class CopyOf2 {
    static void l(String name, List<Integer> c) {
        List<Integer> d;
        try {
            d = List.copyOf(c);
        }
        catch (RuntimeException e) {
            System.out.println("List " + name + " " + e.getClass().getName());
            return;
        }
        System.out.println("List " + name + " " + (d == c) + " " + (List.copyOf(d) == d) + " " + d);
    }

    static void s(String name, Set<Integer> c) {
        Set<Integer> d;
        try {
            d = Set.copyOf(c);
        }
        catch (RuntimeException e) {
            System.out.println("Set " + name + " " + e.getClass().getName());
            return;
        }
        System.out.println("Set " + name + " " + (d == c) + " " + (Set.copyOf(d) == d) + " " + d.size());
    }

    static void m(String name, Map<Integer, Integer> c) {
        Map<Integer, Integer> d;
        try {
            d = Map.copyOf(c);
        }
        catch (RuntimeException e) {
            System.out.println("Map " + name + " " + e.getClass().getName());
            return;
        }
        System.out.println("Map " + name + " " + (d == c) + " " + (Map.copyOf(d) == d) + " " + d.size());
    }

    public static void main(String[] args) {
        l("of0", List.of());
        l("of1", List.of(1));
        l("of2", List.of(1, 2));
        l("of3", List.of(1, 2, 3));
        l("ofArray", List.of(new Integer[] {1, 2, 3, 4}));
        l("copyOfArrayList", List.copyOf(new ArrayList<>(List.of(1, 2))));
        l("copyOfEmptyArrayList", List.copyOf(new ArrayList<>()));
        l("toUnmodifiableList", Stream.of(1, 2).collect(Collectors.toUnmodifiableList()));
        l("toUnmodifiableList0", Stream.<Integer>of().collect(Collectors.toUnmodifiableList()));
        l("toList", Stream.of(1, 2).toList());
        l("toList0", Stream.<Integer>of().toList());
        l("toListNull", Stream.of(1, null).toList());
        l("sub", List.of(1, 2, 3).subList(0, 2));
        l("sub0", List.of(1, 2, 3).subList(0, 0));
        l("subAll", List.of(1, 2, 3).subList(0, 3));
        l("reversed", List.of(1, 2, 3).reversed());
        l("unmodifiable", Collections.unmodifiableList(List.of(1, 2)));
        l("empty", Collections.emptyList());
        l("singleton", Collections.singletonList(1));
        l("nCopies", Collections.nCopies(2, 1));
        l("asList", Arrays.asList(1, 2));
        l("window", Stream.of(1, 2, 3).gather(Gatherers.windowFixed(2)).findFirst().get());
        l("streamToListOfSub", List.of(1, 2, 3).subList(1, 3).stream().toList());
        s("of0", Set.of());
        s("of1", Set.of(1));
        s("of2", Set.of(1, 2));
        s("of3", Set.of(1, 2, 3));
        s("copyOfHashSet", Set.copyOf(new HashSet<>(List.of(1, 2))));
        s("copyOfDup", Set.copyOf(List.of(1, 1, 2)));
        s("toUnmodifiableSet", Stream.of(1, 2).collect(Collectors.toUnmodifiableSet()));
        s("toUnmodifiableSet0", Stream.<Integer>of().collect(Collectors.toUnmodifiableSet()));
        s("keySet", Map.of(1, 2, 3, 4).keySet());
        s("keySet1", Map.of(1, 2).keySet());
        s("unmodifiable", Collections.unmodifiableSet(Set.of(1, 2)));
        s("empty", Collections.emptySet());
        s("singleton", Collections.singleton(1));
        m("of0", Map.of());
        m("of1", Map.of(1, 2));
        m("of2", Map.of(1, 2, 3, 4));
        m("ofEntries", Map.ofEntries(Map.entry(1, 2), Map.entry(3, 4)));
        m("ofEntries0", Map.ofEntries());
        m("copyOfHashMap", Map.copyOf(new HashMap<>(Map.of(1, 2))));
        m("toUnmodifiableMap", Stream.of(1, 2).collect(Collectors.toUnmodifiableMap(x -> x, x -> x)));
        m("toUnmodifiableMap0", Stream.<Integer>of().collect(Collectors.toUnmodifiableMap(x -> x, x -> x)));
        m("unmodifiable", Collections.unmodifiableMap(Map.of(1, 2)));
        m("empty", Collections.emptyMap());
        m("singleton", Collections.singletonMap(1, 2));
        System.out.println((List.of() == List.of()) + " " + (Set.of() == Set.of()) + " " + (Map.of() == Map.of()));
        System.out.println((List.copyOf(new ArrayList<>()) == List.of()) + " " + (Set.copyOf(new HashSet<>()) == Set.of()) + " " + (Map.copyOf(new HashMap<>()) == Map.of()));
        System.out.println((Stream.of().toList() == List.of()) + " " + (Stream.of().toList() == Stream.of().toList()) + " " + (Stream.of().collect(Collectors.toUnmodifiableList()) == List.of()));
        System.out.println((Map.ofEntries() == Map.of()) + " " + (Stream.of().collect(Collectors.toUnmodifiableSet()) == Set.of()) + " " + (List.of(new Integer[0]) == List.<Integer>of()));
        System.out.println((List.of(1, 2, 3).subList(0, 0) == List.<Integer>of()) + " " + (List.of(1, 2).subList(0, 0) == List.<Integer>of()) + " " + (List.of(1).reversed() == List.of(1).reversed()));
    }
}
