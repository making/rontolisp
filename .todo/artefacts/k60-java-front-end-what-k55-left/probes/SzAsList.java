import java.util.*;

public class SzAsList {
    public static void main(String[] args) {
        List<String> fixed = Arrays.asList("a", "b", "c");
        List<String> grown = new ArrayList<>(List.of("d", "e"));
        StringBuilder out = new StringBuilder();
        for (String s : fixed) {
            out.append(s);
        }
        for (String s : grown) {
            out.append(s);
        }
        System.out.println(out);
    }
}
