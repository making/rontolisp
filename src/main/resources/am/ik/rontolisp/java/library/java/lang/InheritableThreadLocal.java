// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A thread's values are kept by its Thread object, apart from a ThreadLocal's: a thread
// takes over the values of the thread making it, each through childValue.
package java.lang;

import java.util.HashMap;
import java.util.Map;

public class InheritableThreadLocal<T> extends ThreadLocal<T> {

    private static final class Values implements Thread.Inherited {

        final HashMap<InheritableThreadLocal<?>, Object> held = new HashMap<>();

        public Thread.Inherited child() {
            Values taken = new Values();
            for (Map.Entry<InheritableThreadLocal<?>, Object> each : this.held.entrySet()) {
                taken.held.put(each.getKey(), each.getKey().childOf(each.getValue()));
            }
            return taken;
        }
    }

    public InheritableThreadLocal() {
    }

    protected T childValue(T parentValue) {
        return parentValue;
    }

    @SuppressWarnings("unchecked")
    private Object childOf(Object parentValue) {
        return childValue((T) parentValue);
    }

    @SuppressWarnings("unchecked")
    public T get() {
        Values values = (Values) Thread.currentThread().inherited;
        if (values == null || !values.held.containsKey(this)) {
            T value = initialValue();
            set(value);
            return value;
        }
        return (T) values.held.get(this);
    }

    public void set(T value) {
        Thread thread = Thread.currentThread();
        if (thread.inherited == null) {
            thread.inherited = new Values();
        }
        ((Values) thread.inherited).held.put(this, value);
    }

    public void remove() {
        Values values = (Values) Thread.currentThread().inherited;
        if (values != null) {
            values.held.remove(this);
        }
    }
}
