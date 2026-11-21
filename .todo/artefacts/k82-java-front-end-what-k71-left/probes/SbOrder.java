import java.util.*;

public class SbOrder {
    static <T extends Comparable<? super T>> T least(List<T> items) {
        return Collections.min(items);
    }

    public static void main(String[] args) {
        List<StringBuilder> words = new ArrayList<>(List.of(new StringBuilder("pear"), new StringBuilder("apple"), new StringBuilder("fig")));
        Collections.sort(words);
        TreeSet<StringBuffer> set = new TreeSet<>(List.of(new StringBuffer("b"), new StringBuffer("a"), new StringBuffer("b")));
        Comparable<StringBuilder> c = new StringBuilder("m");
        Object raw = new StringBuilder("x");
        String refused;
        try {
            ((Comparable) raw).compareTo("x");
            refused = "taken";
        } catch (ClassCastException e) {
            refused = "ClassCastException";
        }
        System.out.println(words + " " + set + " " + least(words) + " " + c.compareTo(new StringBuilder("a")) + " " + (raw instanceof Comparable) + " " + refused + " " + words.stream().max(Comparator.naturalOrder()).get());
    }
}
