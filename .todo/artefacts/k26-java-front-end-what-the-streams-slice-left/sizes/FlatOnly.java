import java.util.*;
import java.util.stream.*;

public class FlatOnly {
    public static void main(String[] args) {
        List<List<Integer>> lists = List.of(List.of(1, 2), List.of(3));
        System.out.println(lists.stream().flatMap(List::stream).filter(x -> x > 1).findFirst().get());
    }
}
