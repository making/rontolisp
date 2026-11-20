import java.util.*;
public class SzOwnClear {
    static class Mine extends AbstractList<Integer> {
        final ArrayList<Integer> in = new ArrayList<>(List.of(0, 1, 2, 3));
        public Integer get(int i) { return in.get(i); }
        public int size() { return in.size(); }
        public Integer remove(int i) { return in.remove(i); }
    }
    public static void main(String[] a) {
        Mine m = new Mine();
        int t = 0;
        for (int x : m) t += x;
        m.clear();
        System.out.println(m + " " + t);
    }
}
