import java.util.*;

public class Dijkstra {
    record Edge(int to, int weight) {
    }

    public static void main(String[] args) {
        int n = 2000;
        Random random = new Random(42);
        List<List<Edge>> graph = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            graph.add(new ArrayList<>());
        }
        for (int i = 0; i < n * 5; i++) {
            int a = random.nextInt(n), b = random.nextInt(n);
            int w = 1 + random.nextInt(100);
            graph.get(a).add(new Edge(b, w));
            graph.get(b).add(new Edge(a, w));
        }
        long[] dist = new long[n];
        Arrays.fill(dist, Long.MAX_VALUE);
        dist[0] = 0;
        PriorityQueue<long[]> queue = new PriorityQueue<>(Comparator.comparingLong(e -> e[0]));
        queue.add(new long[] {0, 0});
        int popped = 0;
        while (!queue.isEmpty()) {
            long[] top = queue.poll();
            popped++;
            int at = (int) top[1];
            if (top[0] > dist[at]) {
                continue;
            }
            for (Edge e : graph.get(at)) {
                long through = dist[at] + e.weight();
                if (through < dist[e.to()]) {
                    dist[e.to()] = through;
                    queue.add(new long[] {through, e.to()});
                }
            }
        }
        long sum = 0;
        long far = 0;
        for (long d : dist) {
            sum += d;
            far = Math.max(far, d);
        }
        System.out.println(popped + " " + sum + " " + far + " " + dist[n - 1]);
        PriorityQueue<String> words = new PriorityQueue<>(List.of("pear", "fig", "apple", "kiwi"));
        System.out.println(words + " " + words.poll() + " " + words);
    }
}
