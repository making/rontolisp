import java.util.*;
public class Thrown {
  static <X extends Exception> void thrower(List<X> l) throws X {}
  static <X extends Exception> void thrower2(List<X> l, X x) throws X {}
  public static void main(String[] args) {
    List raw = new ArrayList(List.of(3, 1, 2));
    thrower(raw);
    thrower2(raw, new RuntimeException());
  }
}
