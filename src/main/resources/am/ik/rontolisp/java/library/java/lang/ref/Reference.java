// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.ref;

// A reference to an object. The collector of no target clears one: a collector may keep
// what it could reclaim, as one that reclaims nothing does, so the program sees the
// object until it clears the reference itself (clear, enqueue). Only enqueue() puts a
// reference on the queue it was made with, once; a queue answers the last one put on it
// first, as a JDK's does.
public abstract sealed class Reference<T> permits PhantomReference, SoftReference, WeakReference {

    private T referent;

    // the queue it was made with, or null
    final ReferenceQueue<? super T> queue;

    // under the queue's monitor: 0 never put on it, 1 on it now, 2 taken off it
    int state;

    // while on its queue: the one put on it before
    Reference<?> below;

    Reference(T referent, ReferenceQueue<? super T> queue) {
        this.referent = referent;
        this.queue = queue;
    }

    public T get() {
        return this.referent;
    }

    public final boolean refersTo(T obj) {
        return this.referent == obj;
    }

    public void clear() {
        this.referent = null;
    }

    public boolean isEnqueued() {
        ReferenceQueue<? super T> on = this.queue;
        if (on == null) {
            return false;
        }
        synchronized (on) {
            return this.state == 1;
        }
    }

    // clears the reference and puts it on its queue: false where it was made with none or
    // was put on it before
    public boolean enqueue() {
        this.referent = null;
        ReferenceQueue<? super T> to = this.queue;
        return to != null && to.put(this);
    }

    protected Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    // keeps an object from the collector until here, which no collector here takes
    public static void reachabilityFence(Object ref) {
    }
}
