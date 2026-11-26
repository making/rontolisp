// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A lock whose holder is told by a number, with a read that takes nothing and is
// checked afterwards. One monitor: a sequence that moves when the write lock is taken
// and again when it is given back, and a count of readers. The numbers are a JDK 25's,
// read from what it answers: the sequence starts at 256 and moves by 128, so its bit
// 128 says a writer holds the lock; a reader's stamp is the sequence and the count of
// readers there were with it, 126 at most; a stamp is good while its sequence is the
// lock's. A thread holding the lock that asks for it again waits for itself.
package java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;

public class StampedLock {

    private static final long WRITING = 128L;

    /** The part of a stamp that counts readers. */
    private static final long READERS = 127L;

    private long sequence = 256L;

    private int readers;

    private Lock readView;

    private Lock writeView;

    private ReadWriteLock bothViews;

    public StampedLock() {
    }

    private boolean writing() {
        return (this.sequence & WRITING) != 0;
    }

    /** What a reader that came now is told. */
    private long reading() {
        return this.sequence + Math.min(this.readers, 126);
    }

    /** Whether a stamp was given with the sequence the lock has now. */
    private boolean current(long stamp) {
        return (stamp & ~READERS) == this.sequence;
    }

    private void free() {
        this.sequence += WRITING;
        notifyAll();
    }

    // The members that never wait call nothing that can (Bounded says why).

    public synchronized long tryWriteLock() {
        if (writing() || this.readers > 0) {
            return 0L;
        }
        this.sequence += WRITING;
        return this.sequence;
    }

    public synchronized long tryReadLock() {
        if (writing()) {
            return 0L;
        }
        this.readers++;
        return reading();
    }

    public synchronized long tryOptimisticRead() {
        return writing() ? 0L : this.sequence;
    }

    public synchronized boolean validate(long stamp) {
        return current(stamp);
    }

    /** Waits on the lock for a change: false when the time was up already. */
    private boolean pause(long nanos, long deadline) throws InterruptedException {
        if (nanos < 0) {
            wait();
            return true;
        }
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            return false;
        }
        wait((left + 999999L) / 1000000L);
        return true;
    }

    /** Takes the write lock, waiting for ever below 0 nanoseconds, else no longer than that. */
    private long write(long nanos, boolean interruptibly) throws InterruptedException {
        boolean interrupted = false;
        long deadline = System.nanoTime() + nanos;
        long stamp = 0L;
        synchronized (this) {
            while (writing() || this.readers > 0) {
                try {
                    if (!pause(nanos, deadline)) {
                        break;
                    }
                }
                catch (InterruptedException e) {
                    if (interruptibly) {
                        throw e;
                    }
                    interrupted = true;
                }
            }
            if (!writing() && this.readers == 0) {
                this.sequence += WRITING;
                stamp = this.sequence;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return stamp;
    }

    private long read(long nanos, boolean interruptibly) throws InterruptedException {
        boolean interrupted = false;
        long deadline = System.nanoTime() + nanos;
        long stamp = 0L;
        synchronized (this) {
            while (writing()) {
                try {
                    if (!pause(nanos, deadline)) {
                        break;
                    }
                }
                catch (InterruptedException e) {
                    if (interruptibly) {
                        throw e;
                    }
                    interrupted = true;
                }
            }
            if (!writing()) {
                this.readers++;
                stamp = reading();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return stamp;
    }

    private static void interruptible() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    public long writeLock() {
        try {
            return write(-1L, false);
        }
        catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public long writeLockInterruptibly() throws InterruptedException {
        interruptible();
        return write(-1L, true);
    }

    public long tryWriteLock(long time, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(time);
        interruptible();
        return nanos <= 0 ? tryWriteLock() : write(nanos, true);
    }

    public long readLock() {
        try {
            return read(-1L, false);
        }
        catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public long readLockInterruptibly() throws InterruptedException {
        interruptible();
        return read(-1L, true);
    }

    public long tryReadLock(long time, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(time);
        interruptible();
        return nanos <= 0 ? tryReadLock() : read(nanos, true);
    }

    public synchronized void unlockWrite(long stamp) {
        if (!writing() || stamp != this.sequence) {
            throw new IllegalMonitorStateException();
        }
        free();
    }

    public synchronized void unlockRead(long stamp) {
        if (this.readers == 0 || (stamp & READERS) == 0 || !current(stamp)) {
            throw new IllegalMonitorStateException();
        }
        if (--this.readers == 0) {
            notifyAll();
        }
    }

    public synchronized void unlock(long stamp) {
        if ((stamp & WRITING) != 0) {
            unlockWrite(stamp);
        }
        else {
            unlockRead(stamp);
        }
    }

    public synchronized boolean tryUnlockWrite() {
        if (!writing()) {
            return false;
        }
        free();
        return true;
    }

    public synchronized boolean tryUnlockRead() {
        if (this.readers == 0) {
            return false;
        }
        if (--this.readers == 0) {
            notifyAll();
        }
        return true;
    }

    /** The write lock for a stamp of the lock's sequence, when no other thread holds the lock. */
    public synchronized long tryConvertToWriteLock(long stamp) {
        if (!current(stamp)) {
            return 0L;
        }
        if (writing()) {
            return stamp == this.sequence ? stamp : 0L;
        }
        if ((stamp & READERS) == 0 ? this.readers != 0 : this.readers != 1) {
            return 0L;
        }
        this.readers = 0;
        this.sequence += WRITING;
        return this.sequence;
    }

    public synchronized long tryConvertToReadLock(long stamp) {
        if (!current(stamp)) {
            return 0L;
        }
        if (writing()) {
            if (stamp != this.sequence) {
                return 0L;
            }
            free();
            this.readers = 1;
            return reading();
        }
        if ((stamp & READERS) != 0) {
            return this.readers > 0 ? stamp : 0L;
        }
        this.readers++;
        return reading();
    }

    public synchronized long tryConvertToOptimisticRead(long stamp) {
        if (!current(stamp)) {
            return 0L;
        }
        if (writing()) {
            if (stamp != this.sequence) {
                return 0L;
            }
            free();
            return this.sequence;
        }
        if ((stamp & READERS) != 0) {
            if (this.readers == 0) {
                return 0L;
            }
            if (--this.readers == 0) {
                notifyAll();
            }
        }
        return this.sequence;
    }

    public synchronized boolean isWriteLocked() {
        return writing();
    }

    public synchronized boolean isReadLocked() {
        return this.readers > 0;
    }

    public synchronized int getReadLockCount() {
        return this.readers;
    }

    public static boolean isWriteLockStamp(long stamp) {
        return (stamp & (WRITING | READERS)) == WRITING;
    }

    public static boolean isReadLockStamp(long stamp) {
        return (stamp & READERS) != 0;
    }

    public static boolean isLockStamp(long stamp) {
        return (stamp & (WRITING | READERS)) != 0;
    }

    public static boolean isOptimisticReadStamp(long stamp) {
        return (stamp & (WRITING | READERS)) == 0 && stamp != 0;
    }

    public synchronized String toString() {
        return super.toString() + (writing() ? "[Write-locked]"
                : this.readers > 0 ? "[Read-locks:" + this.readers + "]" : "[Unlocked]");
    }

    /** The read lock as a Lock: taken and given back with no stamp. */
    private static final class ReadLockView implements Lock {

        private final StampedLock lock;

        ReadLockView(StampedLock lock) {
            this.lock = lock;
        }

        public void lock() {
            this.lock.readLock();
        }

        public void lockInterruptibly() throws InterruptedException {
            this.lock.readLockInterruptibly();
        }

        public boolean tryLock() {
            return this.lock.tryReadLock() != 0L;
        }

        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            return this.lock.tryReadLock(time, unit) != 0L;
        }

        public void unlock() {
            if (!this.lock.tryUnlockRead()) {
                throw new IllegalMonitorStateException();
            }
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class WriteLockView implements Lock {

        private final StampedLock lock;

        WriteLockView(StampedLock lock) {
            this.lock = lock;
        }

        public void lock() {
            this.lock.writeLock();
        }

        public void lockInterruptibly() throws InterruptedException {
            this.lock.writeLockInterruptibly();
        }

        public boolean tryLock() {
            return this.lock.tryWriteLock() != 0L;
        }

        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            return this.lock.tryWriteLock(time, unit) != 0L;
        }

        public void unlock() {
            if (!this.lock.tryUnlockWrite()) {
                throw new IllegalMonitorStateException();
            }
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ReadWriteLockView implements ReadWriteLock {

        private final StampedLock lock;

        ReadWriteLockView(StampedLock lock) {
            this.lock = lock;
        }

        public Lock readLock() {
            return this.lock.asReadLock();
        }

        public Lock writeLock() {
            return this.lock.asWriteLock();
        }
    }

    public synchronized Lock asReadLock() {
        if (this.readView == null) {
            this.readView = new ReadLockView(this);
        }
        return this.readView;
    }

    public synchronized Lock asWriteLock() {
        if (this.writeView == null) {
            this.writeView = new WriteLockView(this);
        }
        return this.writeView;
    }

    public synchronized ReadWriteLock asReadWriteLock() {
        if (this.bothViews == null) {
            this.bothViews = new ReadWriteLockView(this);
        }
        return this.bothViews;
    }
}
