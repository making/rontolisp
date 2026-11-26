// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.ref;

// The references put on a queue, the last first (a JDK's order). Only a program's
// enqueue() puts one here; remove() waits under the queue's monitor for one, and a
// thread interrupted while none is there gets an InterruptedException.
public class ReferenceQueue<T> {

    private Reference<? extends T> top;

    public ReferenceQueue() {
    }

    // puts a reference made with this queue on it, once
    synchronized boolean put(Reference<?> reference) {
        if (reference.state != 0) {
            return false;
        }
        reference.state = 1;
        reference.below = this.top;
        @SuppressWarnings("unchecked")
        Reference<? extends T> put = (Reference<? extends T>) reference;
        this.top = put;
        notifyAll();
        return true;
    }

    // under the monitor: the reference on top, taken off, or null
    @SuppressWarnings("unchecked")
    private Reference<? extends T> take() {
        Reference<? extends T> taken = this.top;
        if (taken != null) {
            this.top = (Reference<? extends T>) taken.below;
            taken.below = null;
            taken.state = 2;
        }
        return taken;
    }

    public Reference<? extends T> poll() {
        synchronized (this) {
            return take();
        }
    }

    public Reference<? extends T> remove(long timeout) throws IllegalArgumentException, InterruptedException {
        if (timeout < 0) {
            throw new IllegalArgumentException("Negative timeout value");
        }
        synchronized (this) {
            Reference<? extends T> taken = take();
            if (timeout == 0) {
                while (taken == null) {
                    wait();
                    taken = take();
                }
                return taken;
            }
            long start = System.nanoTime();
            long left = timeout;
            while (taken == null) {
                wait(left);
                taken = take();
                long waited = (System.nanoTime() - start) / 1000000L;
                if (taken == null && waited >= timeout) {
                    return null;
                }
                left = timeout - waited;
            }
            return taken;
        }
    }

    public Reference<? extends T> remove() throws InterruptedException {
        return remove(0);
    }
}
