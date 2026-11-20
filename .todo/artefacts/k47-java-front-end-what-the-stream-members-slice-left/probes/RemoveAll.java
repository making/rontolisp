import java.util.*;

public class RemoveAll {
    public static void main(String[] args) {
        for (int n = 1; n <= 3; n++) {
            TreeSet<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            s.addAll(List.of("A", "B"));
            List<String> c = new ArrayList<>(List.of("a", "x", "y").subList(0, n));
            System.out.println(n + " " + s.removeAll(c) + " " + s);
        }
        TreeSet<String> t = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        t.addAll(List.of("A", "B"));
        System.out.println(t.retainAll(List.of("a", "b")) + " " + t + " " + t.containsAll(List.of("a", "b")) + " " + List.of("a").containsAll(t));
        HashSet<String> h = new HashSet<>(List.of("p", "q"));
        System.out.println(h.removeAll(Set.of("p", "z", "w")) + " " + h);
    }
}
