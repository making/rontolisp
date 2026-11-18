import java.util.*;
import java.util.concurrent.*;

public class Sched {
    static final List<String> events = Collections.synchronizedList(new ArrayList<>());

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
            events.add("terminated " + isShutdown() + " " + getPoolSize());
        }
    }

    public static void main(String[] args) throws Exception {
        Asked asked = new Asked(1);
        asked.schedule(() -> events.add("one"), 5, TimeUnit.MILLISECONDS).get();
        asked.submit(() -> { throw new IllegalStateException("in a future"); });
        int[] runs = new int[1];
        CountDownLatch twice = new CountDownLatch(2);
        ScheduledFuture<?> repeating = asked.scheduleAtFixedRate(() -> { runs[0]++; twice.countDown(); }, 1, 5, TimeUnit.MILLISECONDS);
        twice.await();
        repeating.cancel(false);
        asked.shutdown();
        System.out.println(asked.awaitTermination(60, TimeUnit.SECONDS) + " " + asked.isTerminated() + " " + asked.isTerminating());
        long before = 0;
        long after = 0;
        for (String event : events) {
            if (event.startsWith("before")) before++;
            if (event.startsWith("after null")) after++;
        }
        System.out.println(events.subList(0, 5) + " " + (before == after) + " " + (before >= 4) + " " + events.get(events.size() - 1));
        events.clear();
        Asked idle = new Asked(2);
        System.out.println(idle.getPoolSize() + " " + idle.prestartCoreThread() + " " + idle.getPoolSize() + " " + idle.prestartAllCoreThreads() + " " + idle.getPoolSize() + " " + idle.prestartCoreThread() + " " + idle.getCorePoolSize());
        idle.setCorePoolSize(3);
        System.out.println(idle.getCorePoolSize() + " " + idle.prestartAllCoreThreads() + " " + idle.getPoolSize());
        ScheduledFuture<?> far = idle.schedule(() -> events.add("never"), 1, TimeUnit.HOURS);
        ScheduledFuture<?> kept = idle.schedule(() -> events.add("never either"), 1, TimeUnit.HOURS);
        far.cancel(false);
        int queued = idle.getQueue().size();
        idle.purge();
        System.out.println((queued >= 0) + " " + idle.shutdownNow().size() + " " + idle.awaitTermination(60, TimeUnit.SECONDS) + " " + events);
        idle.setThreadFactory(r -> new Thread(r, "later"));
        ScheduledThreadPoolExecutor named = new ScheduledThreadPoolExecutor(1);
        named.setThreadFactory(r -> new Thread(r, "by hand"));
        System.out.println(named.schedule(() -> Thread.currentThread().getName(), 1, TimeUnit.MILLISECONDS).get());
        named.shutdown();
    }
}
