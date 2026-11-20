import java.util.*;
import java.util.concurrent.*;
public class SzCowSub {
    public static void main(String[] a) {
        List<Integer> l = new CopyOnWriteArrayList<>(List.of(1, 2, 3, 4, 5));
        List<Integer> s = l.subList(1, 4);
        int t = 0;
        for (int x : s) t += x;
        s.add(9);
        System.out.println(s + " " + t + " " + l + " " + s.get(0) + s.size());
    }
}
