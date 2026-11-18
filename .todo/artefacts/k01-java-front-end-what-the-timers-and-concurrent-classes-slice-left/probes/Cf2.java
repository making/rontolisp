import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public class Cf2 {
    static String shown(Object o) {
        return String.valueOf(o).replaceAll("@[0-9a-f]+", "@");
    }

    static String tried(Supplier<Object> s) {
        try {
            return shown(s.get());
        }
        catch (Throwable t) {
            return "threw " + t + (t.getCause() != null ? " <- " + t.getCause() : "");
        }
    }

    public static void main(String[] args) throws Exception {
        // exceptionallyAsync
        CompletableFuture<String> failed = new CompletableFuture<>();
        CompletableFuture<String> a = failed.exceptionallyAsync(t -> "saw " + t + " on daemon " + Thread.currentThread().isDaemon());
        failed.completeExceptionally(new IllegalStateException("boom"));
        System.out.println(a.join());
        ExecutorService one = Executors.newFixedThreadPool(1);
        System.out.println(CompletableFuture.<String>failedFuture(new RuntimeException("x"))
            .exceptionallyAsync(t -> Thread.currentThread().getName() + " " + t, one).join().replaceAll("pool-\\d+", "pool-N"));
        System.out.println(CompletableFuture.completedFuture("fine").exceptionallyAsync(t -> "never").join());
        System.out.println(tried(() -> CompletableFuture.<String>failedFuture(new RuntimeException("x"))
            .exceptionallyAsync(t -> { throw new IllegalArgumentException("y"); }).join()));
        System.out.println(tried(() -> CompletableFuture.<String>failedFuture(new RuntimeException("x"))
            .thenApply(s -> s + "!").exceptionallyAsync(t -> "wrapped " + t).join()));
        // exceptionallyCompose
        System.out.println(CompletableFuture.<String>failedFuture(new RuntimeException("x"))
            .exceptionallyCompose(t -> CompletableFuture.completedFuture("composed " + t)).join());
        System.out.println(CompletableFuture.completedFuture("fine").exceptionallyCompose(t -> CompletableFuture.completedFuture("never")).join());
        System.out.println(tried(() -> CompletableFuture.<String>failedFuture(new RuntimeException("x"))
            .exceptionallyCompose(t -> CompletableFuture.<String>failedFuture(new IllegalStateException("inner"))).join()));
        System.out.println(tried(() -> CompletableFuture.<String>failedFuture(new RuntimeException("x"))
            .exceptionallyCompose(t -> { throw new IllegalArgumentException("thrown"); }).join()));
        System.out.println(tried(() -> CompletableFuture.<String>failedFuture(new RuntimeException("x"))
            .exceptionallyCompose(t -> null).join()));
        CompletableFuture<String> later = new CompletableFuture<>();
        CompletableFuture<String> viaLater = CompletableFuture.<String>failedFuture(new RuntimeException("x")).exceptionallyCompose(t -> later);
        System.out.println(viaLater.isDone());
        later.complete("late");
        System.out.println(viaLater.join());
        System.out.println(CompletableFuture.<String>failedFuture(new RuntimeException("x"))
            .exceptionallyComposeAsync(t -> CompletableFuture.completedFuture("async " + Thread.currentThread().isDaemon())).join());
        System.out.println(CompletableFuture.<String>failedFuture(new RuntimeException("x"))
            .exceptionallyComposeAsync(t -> CompletableFuture.completedFuture(Thread.currentThread().getName()), one).join().replaceAll("pool-\\d+", "pool-N"));
        System.out.println(tried(() -> CompletableFuture.completedFuture("v").exceptionallyCompose(null)));
        System.out.println(tried(() -> CompletableFuture.completedFuture("v").exceptionallyAsync(null)));
        // obtrude
        CompletableFuture<String> o = CompletableFuture.completedFuture("first");
        CompletableFuture<String> dep = o.thenApply(s -> s + "!");
        o.obtrudeValue("second");
        System.out.println(o.join() + " " + dep.join() + " " + o.thenApply(s -> s + "?").join());
        o.obtrudeException(new IllegalStateException("forced"));
        System.out.println(tried(o::join) + " " + o.isCompletedExceptionally() + " " + shown(o));
        o.obtrudeValue(null);
        System.out.println(o.join() + " " + o.isCompletedExceptionally() + " " + shown(o));
        CompletableFuture<String> open = new CompletableFuture<>();
        CompletableFuture<String> openDep = open.thenApply(s -> s + "!");
        open.obtrudeValue("set");
        System.out.println(open.join() + " " + openDep.join() + " " + open.complete("again"));
        CompletableFuture<String> open2 = new CompletableFuture<>();
        open2.obtrudeException(new CancellationException("c"));
        System.out.println(open2.isCancelled() + " " + tried(open2::join) + " " + open2.state());
        System.out.println(tried(() -> { open2.obtrudeException(null); return "ok"; }));
        // minimal stages
        CompletableFuture<String> src = new CompletableFuture<>();
        CompletionStage<String> minimal = src.minimalCompletionStage();
        System.out.println(shown(minimal));
        System.out.println(minimal instanceof CompletableFuture);
        CompletableFuture<String> asFuture = (CompletableFuture<String>) minimal;
        System.out.println(tried(() -> asFuture.complete("no")));
        System.out.println(tried(() -> asFuture.completeExceptionally(new RuntimeException())));
        System.out.println(tried(() -> asFuture.join()));
        System.out.println(tried(() -> asFuture.getNow("x")));
        System.out.println(tried(() -> asFuture.isDone()));
        System.out.println(tried(() -> asFuture.isCancelled()));
        System.out.println(tried(() -> asFuture.isCompletedExceptionally()));
        System.out.println(tried(() -> asFuture.cancel(true)));
        System.out.println(tried(() -> asFuture.getNumberOfDependents()));
        System.out.println(tried(() -> { asFuture.obtrudeValue("x"); return "ok"; }));
        System.out.println(tried(() -> { asFuture.obtrudeException(new RuntimeException()); return "ok"; }));
        System.out.println(tried(() -> asFuture.state()));
        System.out.println(tried(() -> asFuture.resultNow()));
        System.out.println(tried(() -> asFuture.exceptionNow()));
        System.out.println(tried(() -> asFuture.orTimeout(1, TimeUnit.SECONDS)));
        System.out.println(tried(() -> asFuture.completeOnTimeout("x", 1, TimeUnit.SECONDS)));
        System.out.println(tried(() -> asFuture.completeAsync(() -> "x")));
        System.out.println(tried(() -> { try { return asFuture.get(); } catch (Exception e) { return "checked " + e; } }));
        System.out.println(tried(() -> { try { return asFuture.get(1, TimeUnit.SECONDS); } catch (Exception e) { return "checked " + e; } }));
        System.out.println(tried(() -> asFuture.copy()));
        System.out.println(tried(() -> asFuture.minimalCompletionStage()));
        CompletionStage<String> next = minimal.thenApply(s -> s + "!");
        System.out.println(shown(next) + " " + (next == minimal));
        CompletableFuture<String> back = minimal.toCompletableFuture();
        System.out.println(shown(back) + " " + (back == src) + " " + (back == (Object) minimal));
        src.complete("done");
        System.out.println(back.join() + " " + next.toCompletableFuture().join() + " " + shown(minimal) + " " + shown(next));
        CompletableFuture<String> bad = new CompletableFuture<>();
        CompletionStage<String> badStage = bad.minimalCompletionStage();
        bad.completeExceptionally(new IllegalStateException("bad"));
        System.out.println(tried(() -> badStage.toCompletableFuture().join()) + " " + shown(badStage));
        System.out.println(tried(() -> badStage.exceptionally(t -> "seen " + t).toCompletableFuture().join()));
        CompletionStage<String> done = CompletableFuture.completedStage("stage");
        System.out.println(shown(done) + " " + done.toCompletableFuture().join());
        CompletionStage<String> lost = CompletableFuture.failedStage(new IllegalStateException("lost"));
        System.out.println(shown(lost) + " " + tried(() -> lost.toCompletableFuture().join()));
        System.out.println(tried(() -> CompletableFuture.failedStage(null)));
        System.out.println(shown(CompletableFuture.completedStage(null)));
        CompletableFuture<String> made = src.newIncompleteFuture();
        System.out.println(shown(made));
        System.out.println(src.defaultExecutor() == ForkJoinPool.commonPool());
        // a subclass decides what its stages are
        class Mine<T> extends CompletableFuture<T> {
            public <U> CompletableFuture<U> newIncompleteFuture() { return new Mine<U>(); }
        }
        Mine<String> mine = new Mine<>();
        CompletableFuture<String> stage = mine.thenApply(s -> s + "!");
        System.out.println((stage instanceof Mine) + " " + (mine.copy() instanceof Mine) + " " + (mine.exceptionally(t -> "") instanceof Mine)
            + " " + (mine.thenCombine(CompletableFuture.completedFuture(1), (x, y) -> x + y) instanceof Mine)
            + " " + (mine.minimalCompletionStage() instanceof Mine) + " " + (mine.thenCompose(s -> CompletableFuture.completedFuture(s)) instanceof Mine)
            + " " + (mine.handle((v, t) -> v) instanceof Mine) + " " + (mine.whenComplete((v, t) -> {}) instanceof Mine)
            + " " + (mine.thenAccept(v -> {}) instanceof Mine) + " " + (mine.thenRun(() -> {}) instanceof Mine)
            + " " + (mine.applyToEither(new CompletableFuture<String>(), v -> v) instanceof Mine)
            + " " + (mine.completeAsync(() -> "x") == mine));
        one.shutdown();
    }
}
