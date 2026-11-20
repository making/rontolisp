import java.util.*;

public class SortCalls {
    public static void main(String[] args) {
        List<Integer> l = new ArrayList<>(List.of(3, 1, 2, 5, 4));
        StringBuilder calls = new StringBuilder();
        l.sort((a, b) -> { calls.append(a).append(b).append(' '); return a - b; });
        System.out.println(l + " " + calls);
    }
}
