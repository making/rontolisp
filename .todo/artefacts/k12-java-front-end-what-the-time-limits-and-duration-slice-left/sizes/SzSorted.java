import java.util.*;
import java.util.stream.*;

public class SzSorted {
    public static void main(String[] args) {
        List<String> words = new ArrayList<>();
        words.add("b");
        words.add("a");
        System.out.println(words.stream().sorted().collect(Collectors.joining(",")));
    }
}
