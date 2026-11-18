import java.util.concurrent.*;

public class Cf {
    public static void main(String[] args) throws Exception {
        CompletableFuture<Integer> answer = CompletableFuture.supplyAsync(() -> 6).thenApply(x -> x * 7);
        System.out.println(answer.thenCombine(CompletableFuture.completedFuture(1), (a, b) -> a + b).join());
    }
}
