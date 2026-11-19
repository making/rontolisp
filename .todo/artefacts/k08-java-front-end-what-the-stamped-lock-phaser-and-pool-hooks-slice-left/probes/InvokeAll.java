import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// invokeAll with a limit: what is done, what is cancelled, what ran. No order of two
// threads is asked by a time: a task that is to outlast the limit waits for a latch the
// main thread opens after invokeAll has answered.
public class InvokeAll {

    static String told(List<Future<String>> futures) {
        StringBuilder text = new StringBuilder();
        for (Future<String> future : futures) {
            text.append(future.isDone() ? " done" : " open").append(future.isCancelled() ? "/cancelled" : "");
            try {
                text.append('=').append(future.get());
            }
            catch (ExecutionException e) {
                text.append('!').append(e.getCause());
            }
            catch (CancellationException e) {
                text.append('!').append(e.getClass().getSimpleName());
            }
            catch (InterruptedException e) {
                text.append("?interrupted");
            }
        }
        return text.toString();
    }

    static void round(String what, ExecutorService pool, long limit) throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch left = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger interrupted = new AtomicInteger();
        List<Callable<String>> tasks = new ArrayList<>();
        tasks.add(() -> {
            ran.addAndGet(1);
            return "a";
        });
        tasks.add(() -> {
            ran.addAndGet(10);
            try {
                gate.await();
            }
            catch (InterruptedException e) {
                interrupted.incrementAndGet();
            }
            finally {
                left.countDown();
            }
            return "b";
        });
        tasks.add(() -> {
            ran.addAndGet(100);
            throw new IllegalStateException("c");
        });
        tasks.add(() -> {
            ran.addAndGet(1000);
            return "d";
        });
        List<Future<String>> futures = pool.invokeAll(tasks, limit, TimeUnit.MILLISECONDS);
        String told = told(futures);
        if (ran.get() / 10 % 10 == 1) {
            // the task that outlasted the limit has seen its interrupt before the gate opens
            left.await();
        }
        gate.countDown();
        pool.shutdown();
        System.out.println(what + ":" + told + " " + pool.awaitTermination(10, TimeUnit.SECONDS) + " ran "
                + ran.get() + " interrupted " + interrupted.get() + " " + futures.size());
    }

    public static void main(String[] args) throws Exception {
        round("two threads", Executors.newFixedThreadPool(2), 300);
        round("one thread", Executors.newSingleThreadExecutor(), 300);
        round("a cached pool", Executors.newCachedThreadPool(), 300);
        round("no time", Executors.newFixedThreadPool(2), 0);
        round("less than no time", Executors.newFixedThreadPool(2), -5);
        ScheduledExecutorService scheduled = Executors.newScheduledThreadPool(2);
        round("a scheduled pool", scheduled, 300);
        // every task ends within the limit
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Callable<String>> quick = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int n = i;
            quick.add(() -> {
                if (n == 3) {
                    throw new java.io.IOException("three");
                }
                return "q" + n;
            });
        }
        System.out.println("in time:" + told(pool.invokeAll(quick, 1, TimeUnit.MINUTES)));
        System.out.println("none: " + pool.invokeAll(new ArrayList<Callable<String>>(), 1, TimeUnit.SECONDS));
        // the refusals
        try {
            pool.invokeAll(null, 1, TimeUnit.SECONDS);
        }
        catch (NullPointerException e) {
            System.out.println("no tasks: NullPointerException");
        }
        try {
            pool.invokeAll(quick, 1, null);
        }
        catch (NullPointerException e) {
            System.out.println("no unit: NullPointerException");
        }
        List<Callable<String>> holed = new ArrayList<>(quick);
        holed.add(1, null);
        AtomicInteger started = new AtomicInteger();
        holed.set(0, () -> "h" + started.incrementAndGet());
        try {
            pool.invokeAll(holed, 1, TimeUnit.SECONDS);
        }
        catch (NullPointerException e) {
            System.out.println("a task that is null: NullPointerException, started " + started.get());
        }
        // a thread interrupted beforehand
        AtomicInteger begun = new AtomicInteger();
        List<Callable<String>> counted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            counted.add(() -> "n" + begun.incrementAndGet());
        }
        Thread.currentThread().interrupt();
        try {
            System.out.println("interrupted:" + told(pool.invokeAll(counted, 1, TimeUnit.SECONDS)));
        }
        catch (InterruptedException e) {
            System.out.println("interrupted: InterruptedException " + Thread.currentThread().isInterrupted());
        }
        pool.shutdown();
        System.out.println(pool.awaitTermination(10, TimeUnit.SECONDS) + " " + pool.isTerminated());
        // after shutdown
        try {
            System.out.println("shut down:" + told(pool.invokeAll(counted, 1, TimeUnit.SECONDS)));
        }
        catch (RuntimeException e) {
            System.out.println("shut down: " + e.getClass().getSimpleName());
        }
        // join with a duration
        CountDownLatch hold = new CountDownLatch(1);
        Thread waiting = new Thread(() -> {
            try {
                hold.await();
            }
            catch (InterruptedException e) {
                System.out.println("unexpected");
            }
        });
        waiting.start();
        System.out.println(waiting.join(Duration.ofMillis(50)) + " " + waiting.join(Duration.ZERO) + " "
                + waiting.join(Duration.ofSeconds(-1)) + " " + waiting.isAlive());
        hold.countDown();
        System.out.println(waiting.join(Duration.ofSeconds(30)) + " " + waiting.join(Duration.ZERO) + " "
                + waiting.join(Duration.ofSeconds(-1)) + " " + waiting.isAlive());
        Thread unstarted = new Thread(() -> { });
        try {
            System.out.println(unstarted.join(Duration.ofMillis(1)));
        }
        catch (IllegalThreadStateException e) {
            System.out.println("not started: " + e);
        }
        // an interrupt ahead of the join: nothing to a thread that ended, an exception
        // where the join would wait, and where it would not
        Thread.currentThread().interrupt();
        System.out.println(waiting.join(Duration.ofMillis(1)) + " " + Thread.interrupted());
        CountDownLatch again = new CountDownLatch(1);
        Thread held = new Thread(() -> {
            try {
                again.await();
            }
            catch (InterruptedException e) {
                System.out.println("unexpected");
            }
        });
        held.start();
        for (Duration limit : new Duration[] { Duration.ofSeconds(30), Duration.ZERO, Duration.ofNanos(-1) }) {
            Thread.currentThread().interrupt();
            try {
                System.out.println(limit + ": " + held.join(limit) + " " + Thread.interrupted());
            }
            catch (InterruptedException e) {
                System.out.println(limit + ": join interrupted " + Thread.currentThread().isInterrupted());
            }
        }
        System.out.println(held.join(Duration.ofNanos(1)) + " " + held.join(Duration.ofSeconds(Long.MAX_VALUE).negated()));
        again.countDown();
        System.out.println(held.join(Duration.ofSeconds(Long.MAX_VALUE, 999999999)));
        try {
            unstarted.join((Duration) null);
        }
        catch (NullPointerException | IllegalThreadStateException e) {
            System.out.println("not started, no duration: " + e.getClass().getSimpleName());
        }
        try {
            waiting.join((Duration) null);
        }
        catch (NullPointerException e) {
            System.out.println("no duration: NullPointerException");
        }
    }

}
