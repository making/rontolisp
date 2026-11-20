import java.util.*;
public class Diamonds {
  static class Box<T> {
    T held;
    Box(List<T> l) { held = l.get(0); }
  }
  static <S> S id(S s) { return s; }
  public static void main(String[] args) {
    List raw = new ArrayList(List.of(3, 1, 2));
    var a = new Box<>(raw);
    Box<String> b = new Box<>(raw);
    Object c = new Box<>(raw);
    var d = new ArrayList<>(raw);
    List<Integer> e = new ArrayList<>(raw);
    Object f = new ArrayList<>(raw);
    System.out.println(id(new ArrayList<>(raw)));
    new ArrayList<>(raw);
  }
}
