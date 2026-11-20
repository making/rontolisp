import java.util.*;
public class SzVec {
    public static void main(String[] a) {
        Vector<Integer> v = new Vector<>(List.of(1, 2, 3));
        v.add(4);
        System.out.println(v + " " + v.get(0));
    }
}
