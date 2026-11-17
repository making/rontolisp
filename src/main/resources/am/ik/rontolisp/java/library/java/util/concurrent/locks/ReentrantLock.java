// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The lock's state is read and written holding the lock object's monitor, on which the
// threads waiting for the lock wait. A thread waiting on a condition waits on a ticket
// of its own, queued under the lock, so a signal wakes the thread that waited longest.
package java.util.concurrent.locks;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

public class ReentrantLock implements Lock {

    private Thread owner;

    private int holds;

    /** How many threads wait for the lock. */
    private int queued;

    private final boolean fair;

    public ReentrantLock() {
        this(false);
    }

    public ReentrantLock(boolean fair) {
        this.fair = fair;
    }

    public void lock() {
        Thread current = Thread.currentThread();
        boolean interrupted = false;
        synchronized (this) {
            if (this.owner == current) {
                this.holds++;
                return;
            }
            this.queued++;
            while (this.owner != null) {
                try {
                    wait();
                }
                catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            this.queued--;
            this.owner = current;
            this.holds = 1;
        }
        if (interrupted) {
            current.interrupt();
        }
    }

    public void lockInterruptibly() throws InterruptedException {
        acquire(-1L);
    }

    public boolean tryLock() {
        Thread current = Thread.currentThread();
        synchronized (this) {
            if (this.owner == current) {
                this.holds++;
                return true;
            }
            if (this.owner != null) {
                return false;
            }
            this.owner = current;
            this.holds = 1;
            return true;
        }
    }

    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(time);
        return acquire(nanos < 0 ? 0 : nanos);
    }

    /** Takes the lock, waiting no longer than the nanoseconds given; for ever below 0. */
    private boolean acquire(long nanos) throws InterruptedException {
        Thread current = Thread.currentThread();
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long deadline = System.nanoTime() + nanos;
        synchronized (this) {
            if (this.owner == current) {
                this.holds++;
                return true;
            }
            this.queued++;
            try {
                while (this.owner != null) {
                    if (nanos < 0) {
                        wait();
                    }
                    else {
                        long left = deadline - System.nanoTime();
                        if (left <= 0) {
                            return false;
                        }
                        wait((left + 999999L) / 1000000L);
                    }
                }
            }
            finally {
                this.queued--;
            }
            this.owner = current;
            this.holds = 1;
            return true;
        }
    }

    public void unlock() {
        synchronized (this) {
            if (this.owner != Thread.currentThread()) {
                throw new IllegalMonitorStateException();
            }
            if (--this.holds == 0) {
                this.owner = null;
                notify();
            }
        }
    }

    public Condition newCondition() {
        return new Waiting(this);
    }

    public synchronized boolean isLocked() {
        return this.owner != null;
    }

    public synchronized boolean isHeldByCurrentThread() {
        return this.owner == Thread.currentThread();
    }

    public synchronized int getHoldCount() {
        return this.owner == Thread.currentThread() ? this.holds : 0;
    }

    public final boolean isFair() {
        return this.fair;
    }

    public final synchronized boolean hasQueuedThreads() {
        return this.queued > 0;
    }

    public final synchronized int getQueueLength() {
        return this.queued;
    }

    /** Gives the lock up however many times the thread took it: how many. */
    private int release() {
        synchronized (this) {
            if (this.owner != Thread.currentThread()) {
                throw new IllegalMonitorStateException();
            }
            int held = this.holds;
            this.holds = 0;
            this.owner = null;
            notify();
            return held;
        }
    }

    /** Takes the lock again as many times as it was held, whatever interrupts. */
    private void regain(int held) {
        lock();
        synchronized (this) {
            this.holds = held;
        }
    }

    /** One thread's wait on a condition. */
    private static final class Ticket {

        boolean signalled;
    }

    private static final class Waiting implements Condition {

        private final ReentrantLock lock;

        /** The waits nothing signalled yet, oldest first: read and written under the lock. */
        private final ArrayDeque<Ticket> waiting = new ArrayDeque<>();

        Waiting(ReentrantLock lock) {
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
}
