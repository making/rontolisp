import java.util.*;
import java.util.stream.*;

public class SzInts {
    public static void main(String[] args) {
        List<String> words = new ArrayList<>();
        words.add("b");
        words.add("a");
        System.out.println(IntStream.range(0, 10).map(i -> i * i).sum());
    }
}
