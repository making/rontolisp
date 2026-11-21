import java.util.*;

public class SzCopyOf {
    record Order(String id, List<String> items) {
        Order {
            items = List.copyOf(items);
        }
    }

    public static void main(String[] args) {
        List<String> items = new ArrayList<>();
        items.add("a");
        items.add("b");
        Order o = new Order("x", items);
        System.out.println(o + " " + new Order("y", o.items()).items().size());
    }
}
