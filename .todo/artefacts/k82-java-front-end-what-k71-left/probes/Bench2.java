import java.util.*;

public class Bench2 {
    public static void main(String[] args) {
        Random random = new Random(1);
        int[] values = new int[100000];
        long start = System.nanoTime();
        for (int i = 0; i < values.length; i++) {
            values[i] = random.nextInt(1000000);
        }
        long drawn = System.nanoTime();
        PriorityQueue<Integer> queue = new PriorityQueue<>();
        long sum = 0;
        for (int round = 0; round < 10; round++) {
            for (int i = 0; i < values.length; i++) {
                queue.add(values[i]);
            }
            while (!queue.isEmpty()) {
                sum += queue.poll();
            }
        }
        long end = System.nanoTime();
        System.out.println(sum + " draw " + (drawn - start) / 1000000 + " ms, queue " + (end - drawn) / 1000000 + " ms");
    }
}
