// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class Optional<T> {

    private final T value;

    private Optional(T value) {
        this.value = value;
    }

    public static <T> Optional<T> empty() {
        return new Optional<>(null);
    }

    public static <T> Optional<T> of(T value) {
        if (value == null) {
            throw new NullPointerException();
        }
        return new Optional<>(value);
    }

    public static <T> Optional<T> ofNullable(T value) {
        return new Optional<>(value);
    }

    public boolean isPresent() {
        return value != null;
    }

    public boolean isEmpty() {
        return value == null;
    }

    public T get() {
        if (value == null) {
            throw new NoSuchElementException("No value present");
        }
        return value;
    }

    public T orElseThrow() {
        if (value == null) {
            throw new NoSuchElementException("No value present");
        }
        return value;
    }

    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exception) throws X {
        if (value == null) {
            throw exception.get();
        }
        return value;
    }

    public T orElse(T other) {
        return value != null ? value : other;
    }

    public T orElseGet(Supplier<? extends T> other) {
        return value != null ? value : other.get();
    }

    public void ifPresent(Consumer<? super T> action) {
        if (value != null) {
            action.accept(value);
        }
    }

    public void ifPresentOrElse(Consumer<? super T> action, Runnable otherwise) {
        if (value != null) {
            action.accept(value);
        }
        else {
            otherwise.run();
        }
    }

    public Optional<T> filter(Predicate<? super T> test) {
        return value == null || test.test(value) ? this : new Optional<>(null);
    }

    public <U> Optional<U> map(Function<? super T, ? extends U> mapper) {
        if (value == null) {
            return new Optional<>(null);
        }
        return new Optional<>(mapper.apply(value));
    }

    public <U> Optional<U> flatMap(Function<? super T, Optional<U>> mapper) {
        if (value == null) {
            return new Optional<>(null);
        }
        Optional<U> mapped = mapper.apply(value);
        if (mapped == null) {
            throw new NullPointerException();
        }
        return mapped;
    }

    public Optional<T> or(Supplier<Optional<T>> other) {
        return value != null ? this : other.get();
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Optional)) {
            return false;
        }
        Object theirs = ((Optional<?>) o).value;
        return value == null ? theirs == null : value.equals(theirs);
    }

    public int hashCode() {
        return value == null ? 0 : value.hashCode();
    }

    public String toString() {
        return value == null ? "Optional.empty" : "Optional[" + value + "]";
    }
}
