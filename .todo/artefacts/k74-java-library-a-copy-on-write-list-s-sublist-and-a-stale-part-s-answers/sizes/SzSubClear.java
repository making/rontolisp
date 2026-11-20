import java.util.*;
public class SzSubClear {
    public static void main(String[] a) {
        List<Integer> l = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        l.subList(1, 4).clear();
        System.out.println(l);
    }
}
