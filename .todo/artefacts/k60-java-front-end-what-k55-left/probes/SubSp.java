import java.util.*;

public class SubSp {
    public static void main(String[] args) {
        for (List<Integer> l : List.<List<Integer>>of(List.of(1, 2, 3).subList(1, 3), List.of(1, 2, 3).subList(1, 2), List.of(1, 2, 3).subList(1, 1), List.of(1, 2).subList(0, 1), List.of(1, 2, 3, 4).subList(0, 4))) {
            Spliterator<Integer> s = l.spliterator();
            StringBuilder b = new StringBuilder(s.characteristics() + " " + s.estimateSize());
            s.tryAdvance(x -> b.append(" first=").append(x));
            b.append(" then ").append(s.estimateSize());
            System.out.println(l + " " + b + " " + l.getClass().getName() + " " + l.hashCode() + " " + l.equals(new ArrayList<>(l)) + " " + l.isEmpty() + " " + l.contains(2) + " " + l.indexOf(3));
        }
        try { List.of(1, 2, 3).subList(1, 3).get(5); } catch (IndexOutOfBoundsException e) { System.out.println(e); }
        try { List.of(1, 2, 3).subList(0, 0).indexOf(null); } catch (NullPointerException e) { System.out.println(e); }
        try { List.of(1, 2, 3).subList(0, 2).listIterator(3); } catch (IndexOutOfBoundsException e) { System.out.println(e); }
        try { List.of(1, 2, 3).subList(0, 2).toArray(new Integer[0]); System.out.println("ok"); } catch (RuntimeException e) { System.out.println(e); }
        System.out.println(Arrays.toString(List.of(1, 2, 3).subList(1, 3).toArray()) + " " + List.of(1, 2, 3).subList(1, 3).stream().toList());
    }
}
