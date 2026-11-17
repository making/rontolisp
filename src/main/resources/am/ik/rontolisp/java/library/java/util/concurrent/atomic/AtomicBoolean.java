// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// Every operation holds the object's monitor: atomic among the threads of the program
// on every target, with no compare-and-swap of the host's.
package java.util.concurrent.atomic;

public class AtomicBoolean {

    private boolean value;

    public AtomicBoolean() {
    }

    public AtomicBoolean(boolean initialValue) {
        this.value = initialValue;
    }

    public final synchronized boolean get() {
        return this.value;
    }

    public final synchronized void set(boolean newValue) {
        this.value = newValue;
    }

    public final void lazySet(boolean newValue) {
        set(newValue);
    }

    public final synchronized boolean getAndSet(boolean newValue) {
        boolean previous = this.value;
        this.value = newValue;
        return previous;
    }

    public final synchronized boolean compareAndSet(boolean expectedValue, boolean newValue) {
        if (this.value != expectedValue) {
            return false;
        }
        this.value = newValue;
        return true;
    }

    public String toString() {
        return Boolean.toString(get());
    }
}
