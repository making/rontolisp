import java.util.*;
import java.util.stream.*;

public class SzLoop {
    public static void main(String[] args) {
        List<String> words = new ArrayList<>();
        words.add("b");
        words.add("a");
        int n = 0; for (String w : words) { if (w.length() > 0) { n++; } } System.out.println(n);
    }
}
