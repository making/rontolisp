import java.util.*;
import java.util.stream.*;
public class CopyOf {
  public static void main(String[] args) {
    List<Integer> a = List.of(1, 2, 3), b = List.of(1), e = List.of();
    List<Integer> sub = a.subList(0, 2), sub0 = a.subList(0, 0);
    List<Integer> nn = Stream.of(1, 2).toList();
    List<Integer> un = Stream.of(1, 2).collect(Collectors.toUnmodifiableList());
    System.out.println((List.copyOf(a) == a) + " " + (List.copyOf(b) == b) + " " + (List.copyOf(e) == e) + " " + (List.copyOf(sub) == sub) + " " + (List.copyOf(sub0) == sub0) + " " + (List.copyOf(nn) == nn) + " " + (List.copyOf(un) == un));
    Set<Integer> s = Set.of(1, 2); Map<Integer, Integer> m = Map.of(1, 2);
    System.out.println((Set.copyOf(s) == s) + " " + (Map.copyOf(m) == m) + " " + (List.copyOf(Collections.unmodifiableList(a)) == a));
  }
}
