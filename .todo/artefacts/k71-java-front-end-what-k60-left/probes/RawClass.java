import java.util.*;
public class RawClass {
  static class H<T> { H(List<String> c) {} void put(List<String> c) {} static void st(List<String> c) {} }
  public static void main(String[] args) {
    List raw = new ArrayList(List.of(3, 1, 2));
    Object g = new H<>(raw);
    H<Integer> h = new H<Integer>(raw);
    h.put(raw);
    H.st(raw);
  }
}
