import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public class Shows {
    static String tail(Object o) {
        String s = o.toString();
        int at = s.indexOf('@');
        int open = s.indexOf('[', at < 0 ? 0 : at);
        return (at < 0 ? s : s.substring(0, at) + "@" + (open < 0 ? "" : s.substring(open)));
    }
    public static void main(String[] args) throws Exception {
        ReentrantLock lock = new ReentrantLock();
        System.out.println(tail(lock));
        lock.lock();
        System.out.println(tail(lock));
        lock.unlock();
        System.out.println(tail(new CountDownLatch(2)));
        System.out.println(tail(new Semaphore(3)));
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        System.out.println(tail(rw));
        System.out.println(tail(rw.readLock()));
        System.out.println(tail(rw.writeLock()));
        rw.readLock().lock();
        System.out.println(tail(rw) + " " + tail(rw.readLock()));
        rw.readLock().unlock();
        rw.writeLock().lock();
        System.out.println(tail(rw) + " " + tail(rw.writeLock()));
        rw.writeLock().unlock();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        System.out.println(tail(pool));
        Future<Integer> f = pool.submit(() -> 7);
        f.get();
        System.out.println(tail(f));
        Future<Integer> g = pool.submit(() -> { throw new IllegalStateException("boom"); });
        try { g.get(); } catch (ExecutionException e) { }
        System.out.println(tail(g));
        FutureTask<Integer> ft = new FutureTask<>(() -> 1);
        System.out.println(tail(ft).replaceAll("task = .*\\]", "task = ...]"));
        ft.cancel(false);
        System.out.println(tail(ft));
        Thread.sleep(50);
        System.out.println(tail(pool));
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.SECONDS);
        System.out.println(tail(pool));
        CompletableFuture<String> cf = new CompletableFuture<>();
        System.out.println(tail(cf));
        CompletableFuture<String> dep = cf.thenApply(s -> s + "!");
        System.out.println(tail(cf));
        cf.complete("x");
        System.out.println(tail(cf) + " " + tail(dep));
        CompletableFuture<String> bad = new CompletableFuture<>();
        bad.completeExceptionally(new IllegalStateException("no"));
        System.out.println(tail(bad));
        System.out.println(new AtomicIntegerArray(new int[] {1, 2, 3}) + " " + new AtomicLongArray(2) + " " + new AtomicReferenceArray<>(new String[] {"a", null}));
        LongAdder adder = new LongAdder();
        adder.add(5); adder.increment();
        System.out.println(adder + " " + adder.sum() + " " + adder.sumThenReset() + " " + adder.intValue());
        System.out.println(new CopyOnWriteArraySet<>(List.of(3, 1, 3, 2)) + " " + new ConcurrentLinkedDeque<>(List.of(1, 2)) + " " + new LinkedBlockingDeque<>(List.of(4, 5)));
        ScheduledExecutorService s = Executors.newScheduledThreadPool(2);
        System.out.println(tail(s));
        CountDownLatch done = new CountDownLatch(6);
        for (int i = 0; i < 6; i++) {
            int n = i;
            s.schedule(() -> { System.out.println(n + " " + Thread.currentThread().getName()); done.countDown(); }, 40 * (i + 1), TimeUnit.MILLISECONDS);
        }
        done.await();
        ScheduledFuture<?> sf = s.schedule(() -> { }, 1, TimeUnit.HOURS);
        System.out.println(tail(sf).replaceAll("task = .*\\]", "task = ...]") + " " + (sf.getDelay(TimeUnit.MINUTES)));
        s.shutdownNow();
        System.out.println(tail(s) + " " + s.isShutdown());
        Timer timer = new Timer();
        CountDownLatch ticks = new CountDownLatch(3);
        timer.schedule(new TimerTask() { public void run() { System.out.println("tick " + Thread.currentThread().getName() + " " + Thread.currentThread().isDaemon()); ticks.countDown(); } }, 10, 30);
        ticks.await();
        timer.cancel();
        try { timer.schedule(new TimerTask() { public void run() { } }, 1); } catch (IllegalStateException e) { System.out.println(e); }
        CyclicBarrier barrier = new CyclicBarrier(2);
        System.out.println(barrier.getParties() + " " + barrier.getNumberWaiting() + " " + barrier.isBroken());
        Exchanger<String> ex = new Exchanger<>();
        Thread t = new Thread(() -> { try { System.out.println("got " + ex.exchange("b")); } catch (InterruptedException e) { } });
        t.start();
        System.out.println("got " + ex.exchange("a"));
        t.join();
    }
}
