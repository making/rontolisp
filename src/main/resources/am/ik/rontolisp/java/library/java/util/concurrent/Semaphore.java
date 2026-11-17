// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

// Permits counted under the semaphore's monitor, on which a thread asking for more than
// there are waits.
public class Semaphore {

    private int permits;

    private final boolean fair;

    public Semaphore(int permits) {
        this(permits, false);
    }

    public Semaphore(int permits, boolean fair) {
        this.permits = permits;
        this.fair = fair;
    }

    private static void counted(int permits) {
        if (permits < 0) {
            throw new IllegalArgumentException();
        }
    }

    public void acquire() throws InterruptedException {
        acquire(1);
    }

    public void acquire(int permits) throws InterruptedException {
        counted(permits);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        synchronized (this) {
            while (this.permits < permits) {
                wait();
            }
            this.permits -= permits;
        }
    }

    public void acquireUninterruptibly() {
        acquireUninterruptibly(1);
    }

    public void acquireUninterruptibly(int permits) {
        counted(permits);
        boolean interrupted = false;
        synchronized (this) {
            while (this.permits < permits) {
                try {
                    wait();
                }
                catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            this.permits -= permits;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    public synchronized boolean tryAcquire(int permits) {
        counted(permits);
        if (this.permits < permits) {
            return false;
        }
        this.permits -= permits;
        return true;
    }

    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return tryAcquire(1, timeout, unit);
    }

    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException {
        counted(permits);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long deadline = Timing.deadline(timeout, unit);
        synchronized (this) {
            while (this.permits < permits) {
                if (!Timing.await(this, deadline)) {
                    return false;
                }
            }
            this.permits -= permits;
            return true;
        }
    }

    public void release() {
        release(1);
    }

    public synchronized void release(int permits) {
        counted(permits);
        this.permits += permits;
        notifyAll();
    }

    public synchronized int availablePermits() {
        return this.permits;
    }

    public synchronized int drainPermits() {
        int drained = this.permits;
        this.permits = 0;
        return drained;
    }

    public boolean isFair() {
        return this.fair;
    }
}
