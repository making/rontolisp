import java.util.*;
import java.util.stream.*;

public class SzLoopList {
    public static void main(String[] args) {
        List<String> words = new ArrayList<>();
        words.add("b");
        words.add("a");
        List<String> out = new ArrayList<>(); for (String w : words) { out.add(w.toUpperCase()); } System.out.println(out);
    }
}
