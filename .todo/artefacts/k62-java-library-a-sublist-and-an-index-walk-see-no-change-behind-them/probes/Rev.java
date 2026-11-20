import java.util.*;
public class Rev {
    public static void main(String[] a) {
        LinkedList<Integer> l = new LinkedList<>(List.of(1,2,3,4,5));
        for (int[] r : new int[][]{{2,1},{-1,1},{0,9}}) {
            try { System.out.println(l.reversed().subList(r[0], r[1])); } catch (Exception e) { System.out.println(e); }
            try { System.out.println(new ArrayList<>(l).reversed().subList(r[0], r[1])); } catch (Exception e) { System.out.println(e); }
        }
        List<Integer> s = l.reversed().subList(1, 3);
        System.out.println(s + " " + s.getClass().getName() + " " + (s instanceof RandomAccess));
        List<Integer> s2 = new ArrayList<>(l).reversed().subList(1, 3);
        System.out.println(s2 + " " + (s2 instanceof RandomAccess));
    }
}
