import java.util.*;

public class SzListOf {
    public static void main(String[] args) {
        List<String> l = List.of("a", "b", "c");
        System.out.println(l + " " + l.get(1) + " " + l.size());
    }
}
