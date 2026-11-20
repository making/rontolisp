import java.util.*;
public class Types {
  static <T> List<T> wrap(List<T> l) { return l; }
  static List<String> plain(List<String> l) { return l; }
  static <T extends Comparable<? super T>> T top(List<? extends T> l, T other) { return other; }
  public static void main(String[] args) {
    List raw = new ArrayList(List.of(3, 1, 2));
    int a = new ArrayList<>(raw);
    int b = wrap(raw);
    int c = plain(raw);
    int d = Collections.max(raw);
    int e = top(raw, "x");
    int f = Collections.max(raw, null);
    int g = Collections.unmodifiableList(raw);
    int h = List.copyOf(raw);
  }
}
