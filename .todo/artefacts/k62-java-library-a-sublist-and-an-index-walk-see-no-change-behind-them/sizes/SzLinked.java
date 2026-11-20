import java.util.*;
public class SzLinked {
    public static void main(String[] a) {
        List<Integer> l = new LinkedList<>();
        for (int i = 0; i < 5; i++) l.add(i);
        int t = 0;
        for (int x : l) t += x;
        System.out.println(l + " " + t);
    }
}
