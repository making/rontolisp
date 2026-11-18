import java.util.*;
import java.util.concurrent.*;

public class Order {
    static StringBuilder seen = new StringBuilder();
    static <T> T note(String s, T v) { seen.append(s).append(' '); return v; }
    public static void main(String[] args) throws Exception {
        CompletableFuture<String> f = new CompletableFuture<>();
        CompletableFuture<String> d1 = f.thenApply(x -> note("d1", x));
        CompletableFuture<String> d2 = f.thenApply(x -> note("d2", x));
        CompletableFuture<String> d3 = f.thenApply(x -> note("d3", x));
        d1.thenAccept(x -> note("e1a", x));
        d1.thenAccept(x -> note("e1b", x));
        d2.thenAccept(x -> note("e2a", x));
        CompletableFuture<Void> e2b = d2.thenAccept(x -> note("e2b", x));
        d2.thenAccept(x -> note("e2c", x));
        e2b.thenRun(() -> note("g1", 0));
        e2b.thenRun(() -> note("g2", 0));
        d3.thenAccept(x -> note("e3a", x));
        f.complete("v");
        System.out.println(seen);
        seen.setLength(0);
        // three levels in one chain, and what is added to a future that is done
        CompletableFuture<String> g = new CompletableFuture<>();
        g.thenApply(x -> note("a", x)).thenApply(x -> note("b", x)).thenAccept(x -> note("c", x));
        g.whenComplete((x, e) -> note("w", x));
        g.handle((x, e) -> note("h", x));
        g.complete("v");
        g.thenAccept(x -> note("late", x));
        System.out.println(seen);
        seen.setLength(0);
        CompletableFuture<String> p = new CompletableFuture<>();
        CompletableFuture<String> q = new CompletableFuture<>();
        p.thenCombine(q, (x, y) -> note("combine1", x + y));
        q.thenCombine(p, (x, y) -> note("combine2", x + y));
        p.thenAccept(x -> note("p1", x));
        CompletableFuture.allOf(p, q).thenRun(() -> note("all", 0));
        CompletableFuture.anyOf(p, q).thenRun(() -> note("any", 0));
        q.thenAccept(x -> note("q1", x));
        p.complete("P");
        seen.append("| ");
        q.complete("Q");
        System.out.println(seen);
    }
}
