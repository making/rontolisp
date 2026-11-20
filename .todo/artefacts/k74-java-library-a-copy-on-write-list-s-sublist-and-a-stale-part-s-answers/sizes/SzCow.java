import java.util.*;
import java.util.concurrent.*;
public class SzCow {
    public static void main(String[] a) {
        List<Integer> l = new CopyOnWriteArrayList<>(List.of(1, 2, 3, 4, 5));
        l.add(6);
        int t = 0;
        for (int x : l) t += x;
        System.out.println(l + " " + t + " " + l.get(0) + l.size());
    }
}
