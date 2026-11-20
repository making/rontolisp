import java.util.*;

public class SzReversed {
    public static void main(String[] args) {
        List<String> words = new ArrayList<>(List.of("b", "a", "c"));
        System.out.println(words.reversed() + " " + words.reversed().get(0));
    }
}
