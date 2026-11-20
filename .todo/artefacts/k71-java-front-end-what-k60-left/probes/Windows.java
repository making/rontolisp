import java.util.*;
import java.util.stream.*;
public class Windows {
  public static void main(String[] args) {
    List<List<Integer>> w = Stream.of(1, null, 3, 4, 5).gather(Gatherers.windowFixed(2)).toList();
    System.out.println(w + " " + w.get(0).getClass().getName() + " " + w.get(2).getClass().getName());
    List<List<Integer>> s = Stream.of(1, null, 3).gather(Gatherers.windowSliding(2)).toList();
    System.out.println(s + " " + s.get(0).getClass().getName());
    try { w.get(0).get(5); } catch (RuntimeException e) { System.out.println(e); }
    System.out.println(w.get(0).contains(null) + " " + w.get(0).indexOf(null));
    try { w.get(0).set(0, 9); } catch (RuntimeException e) { System.out.println(e); }
    List<Integer> one = Stream.of(1, 2).gather(Gatherers.windowFixed(5)).toList().get(0);
    System.out.println(one.getClass().getName());
    System.out.println(Stream.of(1, 2).collect(Collectors.toUnmodifiableList()).getClass().getName());
    System.out.println(Collectors.toList().characteristics().getClass().getName());
  }
}
