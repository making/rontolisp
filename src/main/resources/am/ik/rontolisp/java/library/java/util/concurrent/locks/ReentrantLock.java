// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The lock's state is read and written holding the lock object's monitor, on which the
// threads waiting for the lock wait. Its conditions: Waiting.
package java.util.concurrent.locks;

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
        ReentrantLock lock = this;
        return new Waiting(new Waiting.Held() {
            public int release() {
                return lock.release();
            }

            public void regain(int held) {
                lock.regain(held);
            }

            public boolean isHeldByCurrentThread() {
                return lock.isHeldByCurrentThread();
            }
        });
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

    public synchronized String toString() {
        return super.toString()
                + (this.owner == null ? "[Unlocked]" : "[Locked by thread " + this.owner.getName() + "]");
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
}
