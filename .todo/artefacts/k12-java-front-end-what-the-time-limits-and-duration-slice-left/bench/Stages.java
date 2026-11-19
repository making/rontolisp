import java.util.*;
import java.util.stream.*;

public class Stages {
    static long rangeSum(int n) {
        return IntStream.range(0, n).asLongStream().sum();
    }

    static long rangeIntSum(int n) {
        return IntStream.range(0, n).sum();
    }

    static long arraySum(int[] a) {
        return Arrays.stream(a).sum();
    }

    static long arrayMap(int[] a) {
        return Arrays.stream(a).map(i -> i + 3).sum();
    }

    static long arrayMapFilter(int[] a) {
        return Arrays.stream(a).map(i -> i + 3).filter(x -> (x & 1) == 0).sum();
    }

    static long arrayMap3(int[] a) {
        return Arrays.stream(a).map(i -> i + 3).map(i -> i + 3).map(i -> i + 3).sum();
    }

    static long loop(int[] a) {
        int sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] + 3;
        }
        return sum;
    }

    static long listMap(List<Integer> list) {
        return list.stream().map(v -> v + 3).mapToInt(v -> v).sum();
    }

    static long listSum(List<Integer> list) {
        return list.stream().mapToInt(v -> v).sum();
    }

    static long listLoop(List<Integer> list) {
        int sum = 0;
        for (int v : list) {
            sum += v + 3;
        }
        return sum;
    }

    static long listCollect(List<Integer> list) {
        return list.stream().filter(x -> (x & 1) == 0).collect(Collectors.toList()).size();
    }

    static long listForEach(List<Integer> list) {
        int[] sum = new int[1];
        list.stream().forEach(v -> sum[0] += v);
        return sum[0];
    }

    public static void main(String[] args) {
        String which = System.getenv("WHICH") == null ? "none" : System.getenv("WHICH");
        int rounds = System.getenv("ROUNDS") == null ? 20 : Integer.parseInt(System.getenv("ROUNDS"));
        int n = 1_000_000;
        int[] array = new int[n];
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            array[i] = i;
            list.add(i);
        }
        long total = 0;
        long start = System.nanoTime();
        for (int r = 0; r < rounds; r++) {
            total += switch (which) {
                case "rangeSum" -> rangeSum(n);
                case "rangeIntSum" -> rangeIntSum(n);
                case "arraySum" -> arraySum(array);
                case "arrayMap" -> arrayMap(array);
                case "arrayMapFilter" -> arrayMapFilter(array);
                case "arrayMap3" -> arrayMap3(array);
                case "loop" -> loop(array);
                case "listSum" -> listSum(list);
                case "listMap" -> listMap(list);
                case "listLoop" -> listLoop(list);
                case "listCollect" -> listCollect(list);
                case "listForEach" -> listForEach(list);
                default -> 0;
            };
        }
        long nanos = System.nanoTime() - start;
        System.out.println(which + " " + total + " " + nanos / 1000000 + " ms, " + (nanos / (double) rounds / n) + " ns an element");
    }
}
