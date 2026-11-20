import java.util.*;

public class Bench {
    public static void main(String[] args) {
        PriorityQueue<Integer> queue = new PriorityQueue<>();
        Random random = new Random(1);
        long sum = 0;
        long start = System.nanoTime();
        for (int round = 0; round < 10; round++) {
            for (int i = 0; i < 100000; i++) {
                queue.add(random.nextInt(1000000));
            }
            while (!queue.isEmpty()) {
                sum += queue.poll();
            }
        }
        System.out.println(sum + " " + (System.nanoTime() - start) / 1000000 + " ms");
    }
}
