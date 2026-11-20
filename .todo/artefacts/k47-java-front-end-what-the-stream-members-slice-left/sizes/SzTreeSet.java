import java.util.*;

public class SzTreeSet {
    public static void main(String[] args) {
        TreeSet<Integer> s = new TreeSet<>();
        for (int i = 0; i < 20; i++) {
            s.add(i * 7 % 11);
        }
        System.out.println(s + " " + s.first() + " " + s.ceiling(5) + " " + s.headSet(4));
    }
}
