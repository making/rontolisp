import java.util.*;
public class Warnings {
  static class Box<T> {
    T held;
    Box(List<T> l) { held = l.get(0); }
    <U> Box(List<T> l, U u) { held = l.get(0); }
    static <S> Box<S> of(List<S> l) { return new Box<>(l); }
    <V extends Comparable<V>> V pick(List<V> l) { return l.get(0); }
  }
  public static void main(String[] args) {
    List raw = new ArrayList(List.of(3, 1, 2));
    ArrayList<String> a = new ArrayList<String>(raw);
    Box<String> b = new Box<>(raw);
    Box<String> c = new Box<String>(raw);
    Box<String> d = Box.of(raw);
    List<String> ls = new ArrayList<>();
    ls.addAll(raw);
    Box<Integer> e = new Box<>(List.of(1));
    Object f = e.pick(raw);
    Object g = new Box<>(raw, "u");
    System.out.println(a + " " + b.held + " " + c.held + " " + d.held + " " + ls + " " + f + " " + ((Box) g).held);
  }
}
