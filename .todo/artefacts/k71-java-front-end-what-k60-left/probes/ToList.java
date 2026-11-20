import java.util.*;
import java.util.stream.*;
public class ToList {
  static void show(String what, Object o) { System.out.println(what + " = " + o); }
  public static void main(String[] args) {
    List<Integer> a = Stream.of(1, null, 2).toList();
    show("contains(null)", a.contains(null));
    show("indexOf(null)", a.indexOf(null));
    show("lastIndexOf(null)", a.lastIndexOf(null));
    List<Integer> s = a.subList(0, 2);
    show("sub.class", s.getClass().getName());
    show("sub.contains(null)", s.contains(null));
    show("sub.indexOf(null)", s.indexOf(null));
    show("sub", s);
    try { s.get(5); } catch (RuntimeException e) { show("sub.get(5)", e); }
    show("sub.sub", s.subList(1, 2));
    show("spliterator", a.spliterator().characteristics());
    show("of.spliterator", List.of(1, 2, 3).spliterator().characteristics());
    show("empty spliterator", Stream.of().toList().spliterator().characteristics());
    show("one spliterator", Stream.of(1).toList().spliterator().characteristics());
    show("one class", Stream.of(1).toList().getClass().getName());
    show("empty ==", Stream.of().toList() == List.of());
    show("hash", a.hashCode());
    show("eq", a.equals(Arrays.asList(1, null, 2)));
    show("toArray", Arrays.toString(a.toArray()));
    show("copyOf", (Object) null);
    try { List.copyOf(a); } catch (RuntimeException e) { show("List.copyOf(a)", e); }
    show("copyOf of nonnull", List.copyOf(Stream.of(1, 2).toList()) == null);
    List<Integer> b = Stream.of(1, 2).toList();
    show("copyOf same", List.copyOf(b) == b);
    show("copyOf of List.of same", List.copyOf(List.of(1, 2)) == List.of(1, 2));
    List<Integer> c = List.of(1, 2, 3);
    show("copyOf of List.of identical", List.copyOf(c) == c);
    show("IntStream boxed toList class", IntStream.range(0, 3).boxed().toList().getClass().getName());
    show("Collectors.toUnmodifiableList class", Stream.of(1, 2, 3).collect(Collectors.toUnmodifiableList()).getClass().getName());
    try { Stream.of(1, null).collect(Collectors.toUnmodifiableList()); } catch (RuntimeException e) { show("toUnmodifiableList null", e); }
    show("reversed contains null", a.reversed().contains(null));
    show("reversed", a.reversed());
    try { a.reversed().indexOf(null); show("rev indexOf", a.reversed().indexOf(null)); } catch (RuntimeException e) { show("rev indexOf", e); }
    show("sublist reversed", s.reversed());
    try { a.listIterator(4); } catch (RuntimeException e) { show("listIterator(4)", e); }
    show("toString", a);
  }
}
