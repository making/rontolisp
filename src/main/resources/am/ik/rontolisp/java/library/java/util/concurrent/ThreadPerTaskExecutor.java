// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.ArrayList;
import java.util.List;

// What Executors.newVirtualThreadPerTaskExecutor and newThreadPerTaskExecutor answer:
// a thread of the factory's for each task, counted under the executor's monitor.
final class ThreadPerTaskExecutor implements ExecutorService {

    private final ThreadFactory factory;

    private final ArrayList<Thread> running = new ArrayList<>();

    private boolean shutdown;

    ThreadPerTaskExecutor(ThreadFactory factory) {
        if (factory == null) {
            throw new NullPointerException();
        }
        this.factory = factory;
    }

    public void execute(Runnable command) {
        if (command == null) {
            throw new NullPointerException();
        }
        Thread thread;
        synchronized (this) {
            if (this.shutdown) {
                throw new RejectedExecutionException();
            }
            Thread[] made = new Thread[1];
            thread = this.factory.newThread(() -> {
                try {
                    command.run();
                }
                finally {
                    ended(made[0]);
                }
            });
            made[0] = thread;
            this.running.add(thread);
        }
        thread.start();
    }

    private synchronized void ended(Thread thread) {
        this.running.remove(thread);
        if (this.running.isEmpty()) {
            notifyAll();
        }
    }

    public synchronized void shutdown() {
        this.shutdown = true;
        notifyAll();
    }

    public synchronized List<Runnable> shutdownNow() {
        this.shutdown = true;
        for (Thread thread : this.running) {
            thread.interrupt();
        }
        notifyAll();
        return new ArrayList<>();
    }

    public synchronized boolean isShutdown() {
        return this.shutdown;
    }

    public synchronized boolean isTerminated() {
        return this.shutdown && this.running.isEmpty();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long deadline = Timing.deadline(timeout, unit);
        synchronized (this) {
            while (!(this.shutdown && this.running.isEmpty())) {
                if (!Timing.await(this, deadline)) {
                    return false;
                }
            }
            return true;
        }
    }
}
