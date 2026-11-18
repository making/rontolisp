// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// One writer or any number of readers. Who holds what is read and written under the
// lock object's monitor, on which the threads waiting for either lock wait. A thread
// asking to read waits while another thread writes, and while one waits to write unless
// it reads already; the writer may read. A thread asking to write waits while any other
// thread reads or writes -- and for ever when it reads itself, as the API says.
package java.util.concurrent.locks;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class ReentrantReadWriteLock implements ReadWriteLock {

    /** How many times one thread holds the read lock. */
    private static final class Reading {

        final Thread thread;

        int holds;

        Reading(Thread thread) {
            this.thread = thread;
        }
    }

    private final ReadLock reader = new ReadLock(this);

    private final WriteLock writer = new WriteLock(this);

    private final boolean fair;

    private Thread writing;

    private int writes;

    /** The threads holding the read lock. */
    private final ArrayList<Reading> reading = new ArrayList<>();

    private int reads;

    /** How many threads wait for the write lock, and how many for either. */
    private int writers;

    private int queued;

    public ReentrantReadWriteLock() {
        this(false);
    }

    public ReentrantReadWriteLock(boolean fair) {
        this.fair = fair;
    }

    public ReadLock readLock() {
        return this.reader;
    }

    public WriteLock writeLock() {
        return this.writer;
    }

    private Reading reading(Thread thread) {
        for (Reading held : this.reading) {
            if (held.thread == thread) {
                return held;
            }
        }
        return null;
    }

    /**
     * Whether a thread may read now, called under the monitor: one that asks without
     * waiting goes ahead of the threads waiting to write.
     */
    // tryLock() never waits and calls nothing that can: take, not acquire
    // (java.util.concurrent.Bounded says why).
    private boolean readable(Thread thread, boolean ahead) {
        if (this.writing != null) {
            return this.writing == thread;
        }
        return ahead || this.writers == 0 || reading(thread) != null;
    }

    private boolean writable(Thread thread) {
        return this.writing == thread || (this.writing == null && this.reads == 0);
    }

    /** Takes one of the two locks when the thread may have it now: whether it did. */
    private synchronized boolean take(boolean write) {
        Thread current = Thread.currentThread();
        if (!(write ? writable(current) : readable(current, true))) {
            return false;
        }
        hold(write, current);
        return true;
    }

    private void hold(boolean write, Thread current) {
        if (write) {
            this.writing = current;
            this.writes++;
        }
        else {
            Reading held = reading(current);
            if (held == null) {
                held = new Reading(current);
                this.reading.add(held);
            }
            held.holds++;
            this.reads++;
        }
    }

    /**
     * Takes one of the two locks, waiting for it: within the nanoseconds given, for
     * ever below 0; an interrupt ends the wait only when it may.
     */
    private boolean acquire(boolean write, long nanos, boolean interruptible) throws InterruptedException {
        Thread current = Thread.currentThread();
        if (interruptible && Thread.interrupted()) {
            throw new InterruptedException();
        }
        long deadline = System.nanoTime() + nanos;
        boolean interrupted = false;
        boolean taken = false;
        synchronized (this) {
            this.queued++;
            if (write) {
                this.writers++;
            }
            try {
                while (!(write ? writable(current) : readable(current, false))) {
                    try {
                        if (nanos < 0) {
                            wait();
                        }
                        else {
                            long left = deadline - System.nanoTime();
                            if (left <= 0) {
                                break;
                            }
                            wait((left + 999999L) / 1000000L);
                        }
                    }
                    catch (InterruptedException e) {
                        if (interruptible) {
                            throw e;
                        }
                        interrupted = true;
                    }
                }
                taken = write ? writable(current) : readable(current, false);
            }
            finally {
                this.queued--;
                if (write) {
                    this.writers--;
                }
                if (!taken) {
                    // a thread that waited to write no longer keeps the readers out
                    notifyAll();
                }
            }
            if (taken) {
                hold(write, current);
            }
        }
        if (interrupted) {
            current.interrupt();
        }
        return taken;
    }

    private synchronized void release(boolean write) {
        Thread current = Thread.currentThread();
        if (write) {
            if (this.writing != current) {
                throw new IllegalMonitorStateException();
            }
            if (--this.writes == 0) {
                this.writing = null;
                notifyAll();
            }
            return;
        }
        Reading held = reading(current);
        if (held == null) {
            throw new IllegalMonitorStateException("attempt to unlock read lock, not locked by current thread");
        }
        if (--held.holds == 0) {
            this.reading.remove(held);
        }
        if (--this.reads == 0) {
            notifyAll();
        }
    }

    public final boolean isFair() {
        return this.fair;
    }

    public synchronized int getReadLockCount() {
        return this.reads;
    }

    public synchronized boolean isWriteLocked() {
        return this.writing != null;
    }

    public synchronized boolean isWriteLockedByCurrentThread() {
        return this.writing == Thread.currentThread();
    }

    public synchronized int getWriteHoldCount() {
        return this.writing == Thread.currentThread() ? this.writes : 0;
    }

    public synchronized int getReadHoldCount() {
        Reading held = reading(Thread.currentThread());
        return held == null ? 0 : held.holds;
    }

    public final synchronized boolean hasQueuedThreads() {
        return this.queued > 0;
    }

    public final synchronized int getQueueLength() {
        return this.queued;
    }

    private static String identity(Object lock) {
        return lock.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(lock));
    }

    public synchronized String toString() {
        return identity(this) + "[Write locks = " + this.writes + ", Read locks = " + this.reads + "]";
    }

    public static class ReadLock implements Lock {

        private final ReentrantReadWriteLock lock;

        protected ReadLock(ReentrantReadWriteLock lock) {
            this.lock = lock;
        }

        public void lock() {
            try {
                this.lock.acquire(false, -1L, false);
            }
            catch (InterruptedException e) {
                // not thrown by a wait no interrupt ends
            }
        }

        public void lockInterruptibly() throws InterruptedException {
            this.lock.acquire(false, -1L, true);
        }

        public boolean tryLock() {
            return this.lock.take(false);
        }

        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            long nanos = unit.toNanos(time);
            return nanos <= 0 ? this.lock.take(false) : this.lock.acquire(false, nanos, true);
        }

        public void unlock() {
            this.lock.release(false);
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }

        public String toString() {
            return identity(this) + "[Read locks = " + this.lock.getReadLockCount() + "]";
        }
    }

    public static class WriteLock implements Lock {

        private final ReentrantReadWriteLock lock;

        protected WriteLock(ReentrantReadWriteLock lock) {
            this.lock = lock;
        }

        public void lock() {
            try {
                this.lock.acquire(true, -1L, false);
            }
            catch (InterruptedException e) {
                // not thrown by a wait no interrupt ends
            }
        }

        public void lockInterruptibly() throws InterruptedException {
            this.lock.acquire(true, -1L, true);
        }

        public boolean tryLock() {
            return this.lock.take(true);
        }

        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            long nanos = unit.toNanos(time);
            return nanos <= 0 ? this.lock.take(true) : this.lock.acquire(true, nanos, true);
        }

        public void unlock() {
            this.lock.release(true);
        }

        public Condition newCondition() {
            ReentrantReadWriteLock lock = this.lock;
            return new Waiting(new Waiting.Held() {
                public int release() {
                    return lock.surrender();
                }

                public void regain(int held) {
                    lock.resume(held);
                }

                public boolean isHeldByCurrentThread() {
                    return lock.isWriteLockedByCurrentThread();
                }
            });
        }

        public boolean isHeldByCurrentThread() {
            return this.lock.isWriteLockedByCurrentThread();
        }

        public int getHoldCount() {
            return this.lock.getWriteHoldCount();
        }

        public String toString() {
            Thread owner = this.lock.owner();
            return identity(this) + (owner == null ? "[Unlocked]" : "[Locked by thread " + owner.getName() + "]");
        }
    }

    private synchronized Thread owner() {
        return this.writing;
    }

    /** A condition's wait: the write lock given up however many times it is held. */
    private synchronized int surrender() {
        if (this.writing != Thread.currentThread()) {
            throw new IllegalMonitorStateException();
        }
        int held = this.writes;
        this.writes = 0;
        this.writing = null;
        notifyAll();
        return held;
    }

    private void resume(int held) {
        try {
            acquire(true, -1L, false);
        }
        catch (InterruptedException e) {
            // not thrown by a wait no interrupt ends
        }
        synchronized (this) {
            this.writes = held;
        }
    }
}
