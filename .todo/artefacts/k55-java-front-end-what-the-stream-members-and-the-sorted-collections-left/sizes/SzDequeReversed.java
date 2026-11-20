import java.util.*;

public class SzDequeReversed {
    public static void main(String[] args) {
        Deque<String> d = new ArrayDeque<>(List.of("a", "b"));
        d.reversed().push("z");
        System.out.println(d + " " + d.reversed());
    }
}
