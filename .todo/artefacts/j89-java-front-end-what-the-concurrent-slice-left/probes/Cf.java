import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

public class Cf {
    static String tail(Object o) {
        String s = o.toString();
        int at = s.indexOf('@');
        int open = s.indexOf('[', at < 0 ? 0 : at);
        return (at < 0 ? s : s.substring(0, at) + "@" + (open < 0 ? "" : s.substring(open)));
    }
    static void attempt(Callable<Object> r) { try { System.out.println(r.call()); } catch (Throwable e) { System.out.println(e + (e.getCause() != null ? " <- " + e.getCause() : "")); } }
    public static void main(String[] args) throws Exception {
        CompletableFuture<String> open = new CompletableFuture<>();
        CompletableFuture<String> done = CompletableFuture.completedFuture("v");
        CompletableFuture<String> failed = CompletableFuture.failedFuture(new IllegalStateException("f"));
        CompletableFuture<String> staged = failed.thenApply(s -> s + "!");
        CompletableFuture<String> cancelled = new CompletableFuture<>();
        cancelled.cancel(true);
        System.out.println(tail(open) + " " + tail(done) + " " + tail(failed) + " " + tail(staged) + " " + tail(cancelled));
        System.out.println(open.state() + " " + done.state() + " " + failed.state() + " " + cancelled.state() + " " + done.resultNow() + " " + failed.exceptionNow() + " " + staged.exceptionNow());
        attempt(() -> open.resultNow());
        attempt(() -> failed.resultNow());
        attempt(() -> cancelled.resultNow());
        attempt(() -> open.exceptionNow());
        attempt(() -> done.exceptionNow());
        attempt(() -> cancelled.exceptionNow());
        ExecutorService one = Executors.newSingleThreadExecutor();
        Future<String> ok = one.submit(() -> "t");
        Future<String> bad = one.submit(() -> { throw new IllegalArgumentException("b"); });
        try { bad.get(); } catch (ExecutionException e) { }
        FutureTask<String> idle = new FutureTask<>(() -> "i");
        FutureTask<String> dropped = new FutureTask<>(() -> "d");
        dropped.cancel(false);
        ok.get();
        System.out.println(ok.state() + " " + bad.state() + " " + idle.state() + " " + dropped.state() + " " + ok.resultNow() + " " + bad.exceptionNow());
        attempt(() -> idle.resultNow());
        attempt(() -> bad.resultNow());
        attempt(() -> dropped.resultNow());
        attempt(() -> idle.exceptionNow());
        attempt(() -> ok.exceptionNow());
        attempt(() -> dropped.exceptionNow());
        attempt(() -> one.invokeAny(List.of(() -> { throw new IllegalStateException("first"); }, () -> "second", () -> "third")));
        attempt(() -> one.invokeAny(List.<Callable<String>>of(() -> { throw new IllegalStateException("first"); }, () -> { throw new IllegalArgumentException("last"); })));
        attempt(() -> one.invokeAny(new ArrayList<Callable<String>>()));
        attempt(() -> one.invokeAny(List.of(() -> { Thread.sleep(2000); return "slow"; }), 30, TimeUnit.MILLISECONDS));
        one.shutdown();
        CompletableFuture<String> a = new CompletableFuture<>();
        CompletableFuture<String> b = new CompletableFuture<>();
        CompletableFuture<Void> both = a.thenAcceptBoth(b, (x, y) -> System.out.println("both " + x + y));
        CompletableFuture<Void> after = a.runAfterBoth(b, () -> System.out.println("after both"));
        CompletableFuture<String> either = a.applyToEither(b, x -> "either " + x);
        CompletableFuture<Void> accepted = a.acceptEither(b, x -> System.out.println("accept " + x));
        CompletableFuture<Void> ran = a.runAfterEither(b, () -> System.out.println("ran after either"));
        System.out.println(a.getNumberOfDependents() + " " + b.getNumberOfDependents() + " " + tail(a));
        b.complete("B");
        System.out.println(either.join() + " " + both.isDone() + " " + accepted.isDone() + " " + ran.isDone());
        a.complete("A");
        System.out.println(both.isDone() + " " + after.isDone() + " " + a.thenCombineAsync(b, (x, y) -> x + y).join());
        CompletableFuture<String> never = new CompletableFuture<>();
        attempt(() -> never.orTimeout(30, TimeUnit.MILLISECONDS).join());
        attempt(() -> never.get());
        System.out.println(new CompletableFuture<String>().completeOnTimeout("fallback", 30, TimeUnit.MILLISECONDS).join() + " " + done.orTimeout(1, TimeUnit.HOURS).join() + " " + done.completeOnTimeout("x", 1, TimeUnit.HOURS).join());
        CompletableFuture<String> quick = new CompletableFuture<String>().orTimeout(1, TimeUnit.HOURS);
        quick.complete("in time");
        System.out.println(quick.join());
        System.out.println(new CompletableFuture<String>().completeAsync(() -> "async").join());
        attempt(() -> new CompletableFuture<String>().completeAsync(() -> { throw new IllegalStateException("x"); }).exceptionally(e -> e.getClass().getSimpleName() + " " + e.getCause()).join());
        long start = System.nanoTime();
        CompletableFuture<String> delayed = CompletableFuture.supplyAsync(() -> "delayed", CompletableFuture.delayedExecutor(40, TimeUnit.MILLISECONDS));
        System.out.println(delayed.join() + " " + ((System.nanoTime() - start) / 1000000 >= 39) + " " + done.copy().join());
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        attempt(() -> { rw.readLock().unlock(); return "unlocked"; });
        attempt(() -> { rw.writeLock().unlock(); return "unlocked"; });
        attempt(() -> rw.readLock().newCondition());
        attempt(() -> new CyclicBarrier(0));
        CyclicBarrier solo = new CyclicBarrier(1, () -> System.out.println("action"));
        System.out.println(solo.await() + " " + solo.await());
        CyclicBarrier two = new CyclicBarrier(2);
        attempt(() -> two.await(20, TimeUnit.MILLISECONDS));
        attempt(() -> two.await());
        System.out.println(two.isBroken());
        two.reset();
        System.out.println(two.isBroken() + " " + two.getNumberWaiting());
        attempt(() -> new Exchanger<String>().exchange("x", 20, TimeUnit.MILLISECONDS));
        LinkedBlockingDeque<Integer> deque = new LinkedBlockingDeque<>(2);
        deque.addFirst(1);
        deque.putLast(2);
        attempt(() -> { deque.addLast(3); return "added"; });
        attempt(() -> deque.add(3));
        System.out.println(deque.offerFirst(0) + " " + deque + " " + deque.pollLast() + " " + deque.takeFirst() + " " + deque.pollFirst(10, TimeUnit.MILLISECONDS) + " " + deque.remainingCapacity());
        attempt(() -> deque.removeFirst());
        attempt(() -> deque.getLast());
    }
}
