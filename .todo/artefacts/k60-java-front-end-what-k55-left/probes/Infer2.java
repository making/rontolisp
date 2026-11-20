import java.util.*;

public class Infer2 {
    record P(String name, int age) {
    }

    public static void main(String[] args) {
        TreeSet<int[]> a = new TreeSet<>((x, y) -> x[0] - y[0]);
        a.add(new int[] {2});
        TreeSet<P> b = new TreeSet<>(Comparator.comparing(P::name));
        b.add(new P("x", 1));
        TreeSet<P> c = new TreeSet<>(Comparator.comparingInt(p -> p.age()));
        c.add(new P("y", 2));
        List<P> d = new ArrayList<>(List.of(new P("z", 3)));
        d.sort(Comparator.comparingInt(p -> p.age()));
        TreeMap<String, Integer> e = new TreeMap<>(Comparator.comparing(s -> s.length()));
        e.put("abc", 1);
        System.out.println(a.size() + " " + b.size() + " " + c.size() + " " + d + " " + e);
    }
}
