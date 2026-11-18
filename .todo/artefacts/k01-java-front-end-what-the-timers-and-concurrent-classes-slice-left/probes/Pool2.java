import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public class Pool2 {
    static String shown(Object o) {
        return String.valueOf(o).replaceAll("@[0-9a-f]+", "@").replaceAll("pool-\\d+", "pool-N");
    }

    static String tried(Supplier<Object> s) {
        try {
            return shown(s.get());
        }
        catch (Throwable t) {
            return "threw " + t + (t.getCause() != null ? " <- " + t.getCause() : "");
        }
    }

    static final List<String> log = Collections.synchronizedList(new ArrayList<>());

    static class Hooked extends ThreadPoolExecutor {
        Hooked(int core, int most) {
            super(core, most, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        protected void beforeExecute(Thread t, Runnable r) {
            log.add("before " + (t == Thread.currentThread()) + " " + (r instanceof FutureTask) + " " + t.getName().replaceAll("pool-\\d+", "pool-N"));
            super.beforeExecute(t, r);
        }

        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);
            log.add("after " + t);
        }

        @Override
        protected void terminated() {
            log.add("terminated " + isShutdown() + " " + isTerminated() + " " + isTerminating() + " " + getPoolSize());
            super.terminated();
        }
    }

    static void dump(String title) {
        System.out.println(title + ": " + log);
        log.clear();
    }

    public static void main(String[] args) throws Exception {
        Hooked hooked = new Hooked(1, 1);
        hooked.execute(() -> log.add("task one"));
        hooked.submit(() -> log.add("task two")).get();
        hooked.execute(() -> { log.add("task three"); throw new IllegalStateException("thrown"); });
        Thread.sleep(100);
        hooked.submit(() -> { throw new IllegalArgumentException("in a future"); });
        hooked.shutdown();
        System.out.println(hooked.awaitTermination(5, TimeUnit.SECONDS));
        dump("hooks");
        // a hook that throws
        ThreadPoolExecutor angry = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
            protected void beforeExecute(Thread t, Runnable r) {
                log.add("before");
                if (log.size() == 1) throw new IllegalStateException("before threw");
            }
            protected void afterExecute(Runnable r, Throwable t) {
                log.add("after " + t);
            }
        };
        angry.execute(() -> log.add("never run"));
        Thread.sleep(100);
        angry.execute(() -> log.add("second runs"));
        angry.shutdown();
        angry.awaitTermination(5, TimeUnit.SECONDS);
        dump("angry");
        System.out.println(angry.getCompletedTaskCount());
        ThreadPoolExecutor after = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
            protected void afterExecute(Runnable r, Throwable t) {
                log.add("after " + t);
                throw new IllegalStateException("after threw");
            }
        };
        after.execute(() -> log.add("ran"));
        Thread.sleep(100);
        after.execute(() -> log.add("ran again"));
        Thread.sleep(100);
        after.shutdown();
        after.awaitTermination(5, TimeUnit.SECONDS);
        dump("after");
        System.out.println(after.getCompletedTaskCount());
        // terminated with shutdownNow, and on a pool that never ran anything
        Hooked idle = new Hooked(2, 2);
        idle.shutdown();
        dump("idle");
        Hooked now = new Hooked(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        now.execute(() -> { started.countDown(); try { Thread.sleep(10000); } catch (InterruptedException e) { log.add("interrupted"); } });
        now.execute(() -> log.add("never"));
        started.await();
        System.out.println(now.shutdownNow().size());
        now.awaitTermination(5, TimeUnit.SECONDS);
        dump("now");
        // setters
        ThreadPoolExecutor p = new ThreadPoolExecutor(1, 2, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        System.out.println(tried(() -> { p.setCorePoolSize(-1); return "ok"; }));
        System.out.println(tried(() -> { p.setCorePoolSize(3); return "ok " + p.getCorePoolSize() + " " + p.getMaximumPoolSize(); }));
        System.out.println(tried(() -> { p.setMaximumPoolSize(0); return "ok"; }));
        System.out.println(tried(() -> { p.setMaximumPoolSize(1); return "ok " + p.getCorePoolSize() + " " + p.getMaximumPoolSize(); }));
        System.out.println(tried(() -> { p.setMaximumPoolSize(4); return "ok " + p.getCorePoolSize() + " " + p.getMaximumPoolSize(); }));
        System.out.println(tried(() -> { p.setCorePoolSize(4); return "ok " + p.getCorePoolSize() + " " + p.getMaximumPoolSize(); }));
        System.out.println(tried(() -> { p.setCorePoolSize(5); return "ok " + p.getCorePoolSize() + " " + p.getMaximumPoolSize(); }));
        System.out.println(tried(() -> { p.setKeepAliveTime(-1, TimeUnit.SECONDS); return "ok"; }));
        System.out.println(tried(() -> { p.setKeepAliveTime(2, TimeUnit.MINUTES); return "ok " + p.getKeepAliveTime(TimeUnit.SECONDS); }));
        System.out.println(tried(() -> { p.allowCoreThreadTimeOut(true); p.setKeepAliveTime(0, TimeUnit.SECONDS); return "ok"; }));
        System.out.println(tried(() -> { p.setKeepAliveTime(1, null); return "ok"; }));
        System.out.println(tried(() -> { p.setThreadFactory(null); return "ok"; }));
        p.allowCoreThreadTimeOut(false);
        p.setThreadFactory(r -> new Thread(r, "made-by-hand"));
        System.out.println(tried(() -> { try { return p.submit(() -> Thread.currentThread().getName()).get(); } catch (Exception e) { return e; } }));
        System.out.println(p.getPoolSize() + " " + p.prestartCoreThread() + " " + p.getPoolSize() + " " + p.prestartAllCoreThreads() + " " + p.getPoolSize() + " " + p.prestartCoreThread() + " " + p.prestartAllCoreThreads());
        p.setCorePoolSize(1);
        Thread.sleep(200);
        System.out.println("shrunk " + p.getPoolSize() + " " + p.getCorePoolSize());
        p.shutdown();
        System.out.println(p.awaitTermination(5, TimeUnit.SECONDS));
        // raising the core size starts workers for what waits
        ThreadPoolExecutor grow = new ThreadPoolExecutor(1, 4, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            grow.execute(() -> { running.incrementAndGet(); try { gate.await(); } catch (InterruptedException e) { } });
        }
        Thread.sleep(100);
        System.out.println("running " + running.get() + " of " + grow.getPoolSize() + " queued " + grow.getQueue().size());
        grow.setCorePoolSize(3);
        Thread.sleep(100);
        System.out.println("running " + running.get() + " of " + grow.getPoolSize() + " queued " + grow.getQueue().size() + " largest " + grow.getLargestPoolSize());
        gate.countDown();
        grow.shutdown();
        grow.awaitTermination(5, TimeUnit.SECONDS);
        // purge
        ThreadPoolExecutor purged = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        CountDownLatch hold = new CountDownLatch(1);
        purged.execute(() -> { try { hold.await(); } catch (InterruptedException e) { } });
        Future<?> f1 = purged.submit(() -> log.add("one"));
        Future<?> f2 = purged.submit(() -> log.add("two"));
        purged.execute(() -> log.add("three"));
        f1.cancel(false);
        System.out.println(purged.getQueue().size());
        purged.purge();
        System.out.println(purged.getQueue().size() + " " + purged.getTaskCount());
        hold.countDown();
        purged.shutdown();
        purged.awaitTermination(5, TimeUnit.SECONDS);
        dump("purged");
        System.out.println(purged.isTerminating() + " " + purged.isTerminated());
        // prestart on a fresh pool: the threads' names, and a pool of core 0
        ThreadPoolExecutor fresh = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        System.out.println(fresh.prestartAllCoreThreads() + " " + fresh.getPoolSize() + " " + fresh.getActiveCount() + " " + shown(fresh));
        System.out.println(tried(() -> { try { return fresh.submit(() -> Thread.currentThread().getName()).get().replaceAll("pool-\\d+", "pool-N"); } catch (Exception e) { return e; } }));
        fresh.shutdown();
        ThreadPoolExecutor none = new ThreadPoolExecutor(0, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        System.out.println(none.prestartCoreThread() + " " + none.prestartAllCoreThreads() + " " + none.getPoolSize());
        none.shutdown();
        // the single-thread wrappers
        ExecutorService single = Executors.newSingleThreadExecutor();
        System.out.println((single instanceof ThreadPoolExecutor) + " " + shown(single).replaceAll("\\[.*", ""));
        System.out.println(single.submit(() -> Thread.currentThread().getName().replaceAll("pool-\\d+", "pool-N")).get());
        System.out.println(single.isShutdown() + " " + single.isTerminated());
        single.shutdown();
        System.out.println(single.awaitTermination(5, TimeUnit.SECONDS) + " " + single.isShutdown() + " " + single.isTerminated());
        System.out.println(tried(() -> single.submit(() -> 1)));
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        System.out.println((sched instanceof ScheduledThreadPoolExecutor) + " " + (sched instanceof ThreadPoolExecutor) + " " + shown(sched).replaceAll("\\[.*", ""));
        System.out.println(sched.schedule(() -> "at its time", 10, TimeUnit.MILLISECONDS).get());
        System.out.println(sched.shutdownNow().size());
        ExecutorService fixed = Executors.newFixedThreadPool(2);
        ExecutorService wrapped = Executors.unconfigurableExecutorService(fixed);
        System.out.println((wrapped instanceof ThreadPoolExecutor) + " " + shown(wrapped).replaceAll("\\[.*", "") + " " + (fixed instanceof ThreadPoolExecutor));
        System.out.println(wrapped.invokeAll(List.of(() -> 1, () -> 2)).size() + " " + wrapped.invokeAny(List.of(() -> "any")));
        wrapped.shutdown();
        System.out.println(fixed.isShutdown());
        ScheduledExecutorService swrapped = Executors.unconfigurableScheduledExecutorService(Executors.newScheduledThreadPool(1));
        System.out.println((swrapped instanceof ScheduledThreadPoolExecutor) + " " + shown(swrapped).replaceAll("\\[.*", ""));
        swrapped.shutdown();
        System.out.println(tried(() -> Executors.unconfigurableExecutorService(null)));
        ExecutorService cached = Executors.newCachedThreadPool();
        System.out.println((cached instanceof ThreadPoolExecutor) + " " + (Executors.newFixedThreadPool(1) instanceof ThreadPoolExecutor) + " " + (Executors.newScheduledThreadPool(1) instanceof ScheduledThreadPoolExecutor));
        try (ExecutorService closing = Executors.newSingleThreadExecutor()) {
            closing.execute(() -> log.add("closed after this"));
        }
        dump("close");
        System.exit(0);
    }
}
