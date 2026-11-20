import java.util.*;
public class Types2 {
  static <T extends Number> T num(List<T> l) { return null; }
  static <T> T first(List<T> l) { return null; }
  static <T extends Comparable<? super T>> T top(List<? extends T> l, T other) { return other; }
  public static void main(String[] args) {
    List raw = new ArrayList(List.of(3, 1, 2));
    String s = Collections.max(raw);
    var v = Collections.max(raw);
    int v1 = v;
    Object o = Collections.max(raw);
    int i1 = num(raw);
    int i2 = first(raw);
    Integer i3 = num(raw);
    String i4 = first(raw);
    Comparable<String> i5 = Collections.max(raw);
    int i6 = top(raw, 3);
    int i7 = Collections.min(raw).compareTo(1);
  }
}
