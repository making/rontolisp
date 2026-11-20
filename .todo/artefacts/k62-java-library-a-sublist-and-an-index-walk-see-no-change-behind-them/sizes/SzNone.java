import java.util.*;
public class SzNone {
    static class Squares extends AbstractList<Integer> {
        public Integer get(int i) { return i * i; }
        public int size() { return 4; }
    }
    public static void main(String[] a) {
        List<Integer> l = new Squares();
        int t = 0;
        for (int x : l) t += x;
        System.out.println(l + " " + t);
    }
}
