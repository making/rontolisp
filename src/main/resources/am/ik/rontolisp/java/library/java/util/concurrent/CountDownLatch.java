// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

// A count the threads in await() wait to reach zero, under the latch's monitor.
public class CountDownLatch {

    private long count;

    public CountDownLatch(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count < 0");
        }
        this.count = count;
    }

    public void await() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        synchronized (this) {
            while (this.count > 0) {
                wait();
            }
        }
    }

    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long deadline = Timing.deadline(timeout, unit);
        synchronized (this) {
            while (this.count > 0) {
                if (!Timing.await(this, deadline)) {
                    return false;
                }
            }
            return true;
        }
    }

    public synchronized void countDown() {
        if (this.count > 0 && --this.count == 0) {
            notifyAll();
        }
    }

    public synchronized long getCount() {
        return this.count;
    }

    public synchronized String toString() {
        return super.toString() + "[Count = " + this.count + "]";
    }
}
