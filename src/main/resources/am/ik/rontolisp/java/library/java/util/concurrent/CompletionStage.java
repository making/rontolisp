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

    <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> fn);

    <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor);

    CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action);

    CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor);

    CompletionStage<Void> thenRunAsync(Runnable action);

    CompletionStage<Void> thenRunAsync(Runnable action, Executor executor);

    <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn);

    <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn,
            Executor executor);

    <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn);

    <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn, Executor executor);

    <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action);

    <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action, Executor executor);

    CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action);

    CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor);

    <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn);

    <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn,
            Executor executor);

    CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action);

    CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action,
            Executor executor);

    CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action);

    CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action, Executor executor);

    <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn);

    <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor);

    CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action);

    CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor);

    CompletionStage<T> exceptionallyAsync(Function<Throwable, ? extends T> fn);

    CompletionStage<T> exceptionallyAsync(Function<Throwable, ? extends T> fn, Executor executor);

    CompletionStage<T> exceptionallyCompose(Function<Throwable, ? extends CompletionStage<T>> fn);

    CompletionStage<T> exceptionallyComposeAsync(Function<Throwable, ? extends CompletionStage<T>> fn);

    CompletionStage<T> exceptionallyComposeAsync(Function<Throwable, ? extends CompletionStage<T>> fn,
            Executor executor);

    CompletableFuture<T> toCompletableFuture();
}
