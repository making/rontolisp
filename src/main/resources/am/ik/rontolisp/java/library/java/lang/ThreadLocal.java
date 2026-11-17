// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A thread's values are kept by its Thread object and read by that thread alone.
package java.lang;

import java.util.function.Supplier;

public class ThreadLocal<T> {

    private final Supplier<? extends T> initial;

    public ThreadLocal() {
        this.initial = null;
    }

    private ThreadLocal(Supplier<? extends T> initial) {
        this.initial = initial;
    }

    public static <S> ThreadLocal<S> withInitial(Supplier<? extends S> supplier) {
        if (supplier == null) {
            throw new NullPointerException();
        }
        return new ThreadLocal<>(supplier);
    }

    protected T initialValue() {
        return this.initial == null ? null : this.initial.get();
    }

    @SuppressWarnings("unchecked")
    public T get() {
        Thread thread = Thread.currentThread();
        if (thread.locals == null || !thread.locals.containsKey(this)) {
            T value = initialValue();
            thread.keep(this, value);
            return value;
        }
        return (T) thread.locals.get(this);
    }

    public void set(T value) {
        Thread.currentThread().keep(this, value);
    }

    public void remove() {
        Thread thread = Thread.currentThread();
        if (thread.locals != null) {
            thread.locals.remove(this);
        }
    }
}
