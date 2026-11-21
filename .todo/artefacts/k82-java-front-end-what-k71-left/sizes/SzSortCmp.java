import java.util.*;

public class SzSortCmp {
    public static void main(String[] args) {
        List<String> l = new ArrayList<>(List.of("pear", "fig", "apple", "kiwi"));
        l.sort(Comparator.comparing(String::length));
        System.out.println(l);
    }
}
