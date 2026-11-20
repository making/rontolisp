import java.util.*;
public class Bounded {
  static <T> void m(Collection<? extends T> c) {}
  static <T extends Number> void n(Collection<? extends T> c) {}
  static void o(Collection<? extends Object> c) {}
  public static void main(String[] args) {
    List raw = new ArrayList(List.of(3, 1, 2));
    m(raw);
    n(raw);
    o(raw);
    Object u = Collections.unmodifiableList(raw);
    List<Object> lo = new ArrayList<>();
    lo.addAll(raw);
  }
}
