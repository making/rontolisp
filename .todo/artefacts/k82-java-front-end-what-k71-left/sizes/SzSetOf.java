import java.util.*;

public class SzSetOf {
    public static void main(String[] args) {
        Set<String> s = Set.of("a", "b", "c");
        System.out.println(s.contains("b") + " " + s.size());
    }
}
