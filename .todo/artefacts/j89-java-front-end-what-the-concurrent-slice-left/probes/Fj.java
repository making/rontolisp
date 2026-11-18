import java.util.*;
import java.util.concurrent.*;

public class Fj {
    static class Sum extends RecursiveTask<Long> {
        final int[] numbers; final int from; final int to;
        Sum(int[] numbers, int from, int to) { this.numbers = numbers; this.from = from; this.to = to; }
        protected Long compute() {
            if (to - from <= 4) {
                long sum = 0;
                for (int i = from; i < to; i++) { sum += numbers[i]; }
                return sum;
            }
            int middle = (from + to) / 2;
            Sum left = new Sum(numbers, from, middle);
            Sum right = new Sum(numbers, middle, to);
            left.fork();
            return right.compute() + left.join();
        }
    }
    static class Fill extends RecursiveAction {
        final int[] numbers; final int from; final int to;
        Fill(int[] numbers, int from, int to) { this.numbers = numbers; this.from = from; this.to = to; }
        protected void compute() {
            if (to - from <= 3) {
                for (int i = from; i < to; i++) { numbers[i] = i * i; }
                return;
            }
            int middle = (from + to) / 2;
            invokeAll(new Fill(numbers, from, middle), new Fill(numbers, middle, to));
        }
    }
    static class Failing extends RecursiveTask<String> {
        final boolean checked;
        Failing(boolean checked) { this.checked = checked; }
        protected String compute() { throw new IllegalStateException("no answer"); }
    }
    static void attempt(Callable<Object> r) { try { System.out.println(r.call()); } catch (Throwable e) { System.out.println(e + (e.getCause() != null ? " <- " + e.getCause() : "")); } }
    public static void main(String[] args) throws Exception {
        int[] numbers = new int[100];
        ForkJoinPool pool = new ForkJoinPool(3);
        pool.invoke(new Fill(numbers, 0, numbers.length));
        Sum sum = new Sum(numbers, 0, numbers.length);
        System.out.println(pool.invoke(sum) + " " + sum.isDone() + " " + sum.isCompletedNormally() + " " + sum.getRawResult() + " " + pool.getParallelism());
        ForkJoinTask<Long> submitted = pool.submit(new Sum(numbers, 0, 50));
        System.out.println(submitted.get() + " " + submitted.join() + " " + pool.submit(() -> "called").get() + " " + pool.submit(() -> { }, "ran").get());
        Sum inline = new Sum(numbers, 10, 20);
        System.out.println(inline.invoke() + " " + ForkJoinTask.inForkJoinPool() + " " + (ForkJoinTask.getPool() == null));
        boolean[] inside = new boolean[2];
        pool.invoke(new RecursiveAction() { protected void compute() { inside[0] = inForkJoinPool(); inside[1] = getPool() == pool; } });
        System.out.println(inside[0] + " " + inside[1]);
        Failing failing = new Failing(false);
        attempt(() -> pool.invoke(failing));
        System.out.println(failing.isCompletedAbnormally() + " " + failing.isCancelled() + " " + failing.getException().getClass().getName() + " " + failing.state());
        attempt(() -> failing.get());
        attempt(() -> failing.join());
        attempt(() -> new Failing(false).invoke());
        Sum never = new Sum(numbers, 0, 4);
        System.out.println(never.cancel(false) + " " + never.isCancelled() + " " + never.isDone() + " " + never.isCompletedAbnormally());
        attempt(() -> never.join());
        attempt(() -> never.get());
        Sum common = new Sum(numbers, 0, numbers.length);
        common.fork();
        System.out.println(common.join() + " " + ForkJoinPool.commonPool().invoke(new Sum(numbers, 0, 10)) + " " + (ForkJoinPool.commonPool() == ForkJoinPool.commonPool()));
        String[] names = new String[2];
        pool.submit(() -> { names[0] = Thread.currentThread().getName().replaceAll("[0-9]+$", "N"); names[1] = "" + Thread.currentThread().isDaemon(); }).get();
        CompletableFuture<String> viaCommon = CompletableFuture.supplyAsync(() -> Thread.currentThread().getName().replaceAll("[0-9]+$", "N"), ForkJoinPool.commonPool());
        System.out.println(names[0] + " " + names[1] + " " + viaCommon.join());
        List<Callable<Integer>> calls = List.of(() -> 1, () -> 2, () -> 3);
        int total = 0;
        for (Future<Integer> each : pool.invokeAll(calls)) { total += each.get(); }
        System.out.println(total + " " + pool.isShutdown());
        pool.shutdown();
        System.out.println(pool.awaitTermination(5, TimeUnit.SECONDS) + " " + pool.isTerminated());
        attempt(() -> pool.invoke(new Sum(numbers, 0, 4)));
        ForkJoinPool.commonPool().shutdown();
        System.out.println(ForkJoinPool.commonPool().isShutdown());
    }
}
