// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

public interface CompletionStage<T> {

    <U> CompletionStage<U> thenApply(Function<? super T, ? extends U> fn);

    CompletionStage<Void> thenAccept(Consumer<? super T> action);

    CompletionStage<Void> thenRun(Runnable action);

    <U> CompletionStage<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn);

    <U, V> CompletionStage<V> thenCombine(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn);

    <U> CompletionStage<Void> thenAcceptBoth(CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action);

    CompletionStage<Void> runAfterBoth(CompletionStage<?> other, Runnable action);

    <U> CompletionStage<U> applyToEither(CompletionStage<? extends T> other, Function<? super T, U> fn);

    CompletionStage<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action);

    CompletionStage<Void> runAfterEither(CompletionStage<?> other, Runnable action);

    <U> CompletionStage<U> handle(BiFunction<? super T, Throwable, ? extends U> fn);

    CompletionStage<T> whenComplete(BiConsumer<? super T, ? super Throwable> action);

    CompletionStage<T> exceptionally(Function<Throwable, ? extends T> fn);

    CompletableFuture<T> toCompletableFuture();
}
