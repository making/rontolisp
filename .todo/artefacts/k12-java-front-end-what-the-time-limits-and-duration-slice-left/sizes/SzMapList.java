import java.util.*;
import java.util.stream.*;

public class SzMapList {
    public static void main(String[] args) {
        List<String> words = new ArrayList<>();
        words.add("b");
        words.add("a");
        System.out.println(words.stream().map(String::toUpperCase).collect(Collectors.toList()));
    }
}
