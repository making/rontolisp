import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Timed {
    static void attempt(Runnable scheduling) {
        try {
            scheduling.run();
            System.out.println("scheduled");
        }
        catch (RuntimeException e) {
            System.out.println(e);
        }
    }

    public static void main(String[] args) throws Exception {
        // a task runs at its time, the soonest first, whichever was handed over first
        ScheduledExecutorService one = Executors.newSingleThreadScheduledExecutor();
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        ScheduledFuture<String> late = one.schedule(() -> {
            seen.add("late");
            return "late on " + Thread.currentThread().getName();
        }, 600, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> soon = one.schedule(() -> {
            seen.add("soon");
        }, 20, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> far = one.schedule(() -> System.out.println("never"), 1, TimeUnit.HOURS);
        System.out.println(late.isDone() + " " + far.getDelay(TimeUnit.MINUTES) + " " + (soon.compareTo(late) < 0) + " "
                + late.compareTo(late) + " " + far.cancel(false) + " " + far.isCancelled());
        System.out.println(late.get() + " " + soon.get() + " " + seen + " " + soon.isDone());

        // a task that repeats runs until it is cancelled, or throws
        CountDownLatch three = new CountDownLatch(3);
        int[] runs = new int[1];
        ScheduledFuture<?> rate = one.scheduleAtFixedRate(() -> {
            if (++runs[0] <= 3) {
                System.out.println("rate " + runs[0]);
                three.countDown();
            }
        }, 0, 15, TimeUnit.MILLISECONDS);
        three.await();
        System.out.println(rate.cancel(false) + " " + rate.isCancelled() + " " + rate.isDone());
        try {
            rate.get();
        }
        catch (CancellationException e) {
            System.out.println("cancelled");
        }
        int[] failing = new int[1];
        ScheduledFuture<?> thrower = one.scheduleWithFixedDelay(() -> {
            if (++failing[0] == 2) {
                throw new IllegalStateException("the second run");
            }
        }, 0, 10, TimeUnit.MILLISECONDS);
        try {
            thrower.get();
        }
        catch (ExecutionException e) {
            System.out.println(e.getCause() + " after " + failing[0]);
        }
        Future<Integer> plain = one.submit(() -> 6 * 7);
        System.out.println(plain.get());

        // after shutdown() a task waiting for its time still runs, one that repeats does not
        ScheduledFuture<?> pending = one.schedule(
                () -> System.out.println("after the shutdown on " + Thread.currentThread().getName()), 300,
                TimeUnit.MILLISECONDS);
        ScheduledFuture<?> repeating = one.scheduleAtFixedRate(() -> System.out.println("never again"), 1, 1,
                TimeUnit.HOURS);
        one.shutdown();
        try {
            one.schedule(() -> {
            }, 1, TimeUnit.MILLISECONDS);
        }
        catch (RejectedExecutionException e) {
            System.out.println("rejected");
        }
        System.out.println(one.isShutdown() + " " + repeating.isCancelled() + " " + pending.isDone());
        System.out.println(one.awaitTermination(60, TimeUnit.SECONDS) + " " + one.isTerminated() + " " + pending.isDone());

        // a pool of two threads; a task cancelled is taken out of it when the pool is told so
        ScheduledThreadPoolExecutor two = new ScheduledThreadPoolExecutor(2);
        two.setRemoveOnCancelPolicy(true);
        ScheduledFuture<?> dropped = two.schedule(() -> {
        }, 1, TimeUnit.HOURS);
        ThreadPoolExecutor pool = two;
        System.out.println(dropped.cancel(false) + " " + dropped.isCancelled() + " " + two.getRemoveOnCancelPolicy() + " "
                + pool.getCorePoolSize());
        CountDownLatch four = new CountDownLatch(4);
        List<String> byTwo = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 4; i++) {
            int n = i;
            two.schedule(() -> {
                byTwo.add(n + " on a thread of the pool " + Thread.currentThread().getName().startsWith("pool-2-thread-"));
                four.countDown();
            }, 20 * (i + 1), TimeUnit.MILLISECONDS);
        }
        four.await();
        Collections.sort(byTwo);
        System.out.println(byTwo);
        two.schedule(() -> System.out.println("dropped"), 1, TimeUnit.HOURS);
        System.out.println(two.shutdownNow().size() + " " + two.awaitTermination(60, TimeUnit.SECONDS));

        // a timer runs its tasks on one thread; a task is scheduled once
        Timer timer = new Timer("ticker", true);
        TimerTask nothing = new TimerTask() {
            public void run() {
            }
        };
        attempt(() -> timer.schedule(nothing, -1));
        attempt(() -> timer.schedule(nothing, 1, 0));
        System.out.println(nothing.cancel() + " " + nothing.scheduledExecutionTime());
        attempt(() -> timer.schedule(nothing, 1));
        CountDownLatch ran = new CountDownLatch(1);
        // what a class does not inherit hides nothing: the state and the period are the program's
        String state = "once";
        int period = 20;
        TimerTask once = new TimerTask() {
            public void run() {
                System.out.println(state + " on " + Thread.currentThread().getName() + ", a daemon "
                        + Thread.currentThread().isDaemon());
                ran.countDown();
            }
        };
        timer.schedule(once, period);
        attempt(() -> timer.schedule(once, period));
        ran.await();
        System.out.println(once.cancel() + " " + (once.scheduledExecutionTime() > 0));
        CountDownLatch ticks = new CountDownLatch(3);
        int[] count = new int[1];
        TimerTask repeated = new TimerTask() {
            public void run() {
                count[0]++;
                System.out.println("run " + count[0]);
                if (count[0] == 3) {
                    System.out.println("cancels itself " + cancel());
                }
                ticks.countDown();
            }
        };
        timer.schedule(repeated, 0, 15);
        ticks.await();
        Thread.sleep(60);
        System.out.println(count[0] + " " + repeated.cancel() + " " + timer.purge());
        TimerTask later = new TimerTask() {
            public void run() {
                System.out.println("never");
            }
        };
        timer.schedule(later, 100000);
        System.out.println(later.cancel() + " " + later.cancel() + " " + timer.purge());
        timer.cancel();
        attempt(() -> timer.schedule(new TimerTask() {
            public void run() {
            }
        }, 1));

        asked();

        // a timer that is no daemon keeps the program until it is cancelled
        Timer second = new Timer();
        second.scheduleAtFixedRate(new TimerTask() {
            int n;

            public void run() {
                System.out.println(Thread.currentThread().getName() + " " + ++n);
                if (n == 2) {
                    second.cancel();
                }
            }
        }, 5, 10);
    }

    static final List<String> events = Collections.synchronizedList(new ArrayList<>());

    /** A scheduled pool that is asked around each run of a task and at its end. */
    static class Asked extends ScheduledThreadPoolExecutor {
        Asked(int size) {
            super(size, r -> new Thread(r, "timed"));
        }

        @Override
        protected void beforeExecute(Thread t, Runnable r) {
            events.add("before " + (t == Thread.currentThread()) + " " + (r instanceof Future) + " " + t.getName());
        }

        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            events.add("after " + t);
        }

        @Override
        protected void terminated() {
            events.add("terminated " + isShutdown() + " " + isTerminated() + " " + getPoolSize());
        }
    }

    // what a scheduled pool is asked, its threads started ahead of a task, its size set later
    static void asked() throws Exception {
        Asked asked = new Asked(1);
        asked.schedule(() -> events.add("one"), 5, TimeUnit.MILLISECONDS).get();
        asked.submit(() -> {
            throw new IllegalStateException("in a future");
        });
        CountDownLatch twice = new CountDownLatch(2);
        ScheduledFuture<?> repeating = asked.scheduleAtFixedRate(twice::countDown, 1, 5, TimeUnit.MILLISECONDS);
        twice.await();
        repeating.cancel(false);
        asked.shutdown();
        System.out.println(asked.awaitTermination(60, TimeUnit.SECONDS) + " " + asked.isTerminated() + " "
                + asked.isTerminating());
        int before = 0;
        int after = 0;
        for (String event : events) {
            if (event.startsWith("before")) {
                before++;
            }
            if (event.equals("after null")) {
                after++;
            }
        }
        System.out.println(events.subList(0, 5) + " " + (before == after) + " " + (before >= 4) + " "
                + events.get(events.size() - 1));
        events.clear();
        Asked idle = new Asked(2);
        System.out.println(idle.getPoolSize() + " " + idle.prestartCoreThread() + " " + idle.getPoolSize() + " "
                + idle.prestartAllCoreThreads() + " " + idle.getPoolSize() + " " + idle.prestartCoreThread() + " "
                + idle.getCorePoolSize());
        idle.setCorePoolSize(3);
        System.out.println(idle.getCorePoolSize() + " " + idle.prestartAllCoreThreads() + " " + idle.getPoolSize());
        ScheduledFuture<?> far = idle.schedule(() -> events.add("never"), 1, TimeUnit.HOURS);
        idle.schedule(() -> events.add("never either"), 1, TimeUnit.HOURS);
        far.cancel(false);
        idle.purge();
        System.out.println(idle.shutdownNow().size() + " " + idle.awaitTermination(60, TimeUnit.SECONDS) + " "
                + events);
        ScheduledThreadPoolExecutor named = new ScheduledThreadPoolExecutor(1);
        named.setThreadFactory(r -> new Thread(r, "by hand"));
        System.out.println(named.schedule(() -> Thread.currentThread().getName(), 1, TimeUnit.MILLISECONDS).get());
        named.shutdown();
    }
}
