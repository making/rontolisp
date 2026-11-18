// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The condition of a lock a thread holds alone: a ReentrantLock's, a write lock's. A
// thread waiting on it waits on a ticket of its own, queued under the lock, so a signal
// wakes the thread that waited longest and one between the lock given up and the wait
// is not lost.
package java.util.concurrent.locks;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

final class Waiting implements Condition {

    /** What a condition asks of its lock. */
    interface Held {

        /** Gives the lock up however many times the thread took it: how many. */
        int release();

        /** Takes the lock again as many times as it was held, whatever interrupts. */
        void regain(int held);

        boolean isHeldByCurrentThread();
    }

    /** One thread's wait on a condition. */
    private static final class Ticket {

        boolean signalled;
    }

    private final Held lock;

    /** The waits nothing signalled yet, oldest first: read and written under the lock. */
    private final ArrayDeque<Ticket> waiting = new ArrayDeque<>();

    Waiting(Held lock) {
        this.lock = lock;
    }

    /**
     * Waits for a signal with the lock given up, and holds it again: 0 when it was
     * signalled, 1 when the time was up first, 2 when the thread was interrupted
     * first. A limit below 0 is none.
     */
    private int park(long nanos, boolean interruptible) {
        Ticket ticket = new Ticket();
        long deadline = System.nanoTime() + nanos;
        this.waiting.add(ticket);
        int held = this.lock.release();
        boolean interrupted = false;
        boolean late = false;
        synchronized (ticket) {
            while (!ticket.signalled && !late && !(interrupted && interruptible)) {
                try {
                    if (nanos < 0) {
                        ticket.wait();
                    }
                    else {
                        long left = deadline - System.nanoTime();
                        if (left <= 0) {
                            late = true;
                        }
                        else {
                            ticket.wait((left + 999999L) / 1000000L);
                        }
                    }
                }
                catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        this.lock.regain(held);
        // no thread signals without the lock, so the ticket is as it will stay
        if (ticket.signalled) {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }
        this.waiting.remove(ticket);
        return interrupted ? 2 : 1;
    }

    public void await() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        if (park(-1L, true) == 2) {
            throw new InterruptedException();
        }
    }

    public void awaitUninterruptibly() {
        park(-1L, false);
    }

    public long awaitNanos(long nanosTimeout) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long deadline = System.nanoTime() + nanosTimeout;
        if (park(nanosTimeout < 0 ? 0 : nanosTimeout, true) == 2) {
            throw new InterruptedException();
        }
        return deadline - System.nanoTime();
    }

    public boolean await(long time, TimeUnit unit) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long nanos = unit.toNanos(time);
        int ended = park(nanos < 0 ? 0 : nanos, true);
        if (ended == 2) {
            throw new InterruptedException();
        }
        return ended == 0;
    }

    private void wake(Ticket ticket) {
        synchronized (ticket) {
            ticket.signalled = true;
            ticket.notify();
        }
    }

    public void signal() {
        if (!this.lock.isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException();
        }
        Ticket first = this.waiting.poll();
        if (first != null) {
            wake(first);
        }
    }

    public void signalAll() {
        if (!this.lock.isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException();
        }
        Ticket next;
        while ((next = this.waiting.poll()) != null) {
            wake(next);
        }
    }
}
