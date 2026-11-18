// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// Two threads hand each other an object. The first to arrive leaves its own under the
// exchanger's monitor and waits there; the second takes it and leaves its own in
// answer. A third waits until the two have ended.
package java.util.concurrent;

public class Exchanger<V> {

    /** A thread waits for another. */
    private boolean offering;

    private V offered;

    /** The other came: what it left. */
    private boolean answered;

    private V answer;

    public Exchanger() {
    }

    public V exchange(V x) throws InterruptedException {
        try {
            return swap(x, -1L);
        }
        catch (TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    public V exchange(V x, long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        long nanos = unit.toNanos(timeout);
        return swap(x, nanos < 0 ? 0 : nanos);
    }

    private synchronized V swap(V mine, long nanos) throws InterruptedException, TimeoutException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long deadline = System.nanoTime() + nanos;
        while (this.offering && this.answered) {
            pause(nanos, deadline);
        }
        if (this.offering) {
            V theirs = this.offered;
            this.answer = mine;
            this.answered = true;
            notifyAll();
            return theirs;
        }
        this.offering = true;
        this.offered = mine;
        InterruptedException interrupted = null;
        boolean late = false;
        while (!this.answered && interrupted == null && !late) {
            try {
                pause(nanos, deadline);
            }
            catch (InterruptedException e) {
                interrupted = e;
            }
            catch (TimeoutException e) {
                late = true;
            }
        }
        // answered or not, the place is the next pair's
        boolean exchanged = this.answered;
        V theirs = this.answer;
        this.offering = false;
        this.offered = null;
        this.answered = false;
        this.answer = null;
        notifyAll();
        if (exchanged) {
            if (interrupted != null) {
                Thread.currentThread().interrupt();
            }
            return theirs;
        }
        if (interrupted != null) {
            throw interrupted;
        }
        throw new TimeoutException();
    }

    private void pause(long nanos, long deadline) throws InterruptedException, TimeoutException {
        if (nanos < 0) {
            wait();
            return;
        }
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            throw new TimeoutException();
        }
        wait((left + 999999L) / 1000000L);
    }
}
