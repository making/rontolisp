import java.util.*;

public class SzPriority {
    public static void main(String[] args) {
        PriorityQueue<Integer> queue = new PriorityQueue<>();
        for (int x : new int[] {5, 3, 8, 1}) {
            queue.add(x);
        }
        StringBuilder out = new StringBuilder();
        while (!queue.isEmpty()) {
            out.append(queue.poll()).append(' ');
        }
        System.out.println(out);
    }
}
