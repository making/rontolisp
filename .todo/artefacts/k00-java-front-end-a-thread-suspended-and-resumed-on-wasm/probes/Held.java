import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class Held {
    static final Object lock = new Object();
    static int shared;
    static final AtomicInteger counter = new AtomicInteger();
    static volatile boolean flag;

    // a region held across a sleep: the other thread's entry blocks
    static void heldAcrossSleep() throws InterruptedException {
        Thread holder = new Thread(() -> {
            synchronized (lock) {
                shared = 1;
                try {
                    Thread.sleep(30);
                }
                catch (InterruptedException e) {
                    return;
                }
                shared = 2;
            }
        });
        holder.start();
        Thread.sleep(5);
        synchronized (lock) {
            System.out.println("held across sleep: " + shared);
        }
        holder.join();
    }

    // a finally that waits, a catch that waits
    static int cleanup(int n) throws InterruptedException {
        try {
            if (n == 1) {
                throw new IllegalStateException("one");
            }
            if (n == 2) {
                return 20;
            }
            Thread.sleep(1);
        }
        catch (IllegalStateException e) {
            Thread.sleep(1);
            System.out.println("caught " + e.getMessage());
            return 10;
        }
        finally {
            Thread.sleep(1);
            System.out.println("finally " + n);
        }
        return 30;
    }

    // a finally that waits while an exception passes
    static void passing() throws InterruptedException {
        try {
            try {
                throw new IllegalArgumentException("passing");
            }
            finally {
                Thread.sleep(1);
                System.out.println("cleanup first");
            }
        }
        catch (IllegalArgumentException e) {
            System.out.println("then " + e.getMessage());
        }
    }

    // a loop left through a finally that waits
    static void leaving() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            try {
                if (i == 1) {
                    continue;
                }
                if (i == 3) {
                    break;
                }
                System.out.println("round " + i);
            }
            finally {
                Thread.sleep(1);
                System.out.println("after " + i);
            }
        }
    }

    // try-with-resources over a pool: close() waits for the tasks
    static void pool() throws Exception {
        Future<Integer> answer;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            answer = pool.submit(() -> {
                Thread.sleep(10);
                return counter.incrementAndGet();
            });
            pool.submit(() -> counter.addAndGet(10));
        }
        System.out.println("pool closed " + answer.isDone() + " " + answer.get() + " " + counter.get());
    }

    // a thread interrupted while it sleeps, while it waits, and one polling an atomic
    static void interrupts() throws InterruptedException {
        Thread sleeper = new Thread(() -> {
            try {
                Thread.sleep(10000);
                System.out.println("not interrupted");
            }
            catch (InterruptedException e) {
                System.out.println("sleep interrupted " + Thread.currentThread().isInterrupted());
            }
        });
        sleeper.start();
        Thread.sleep(5);
        sleeper.interrupt();
        sleeper.join();
        Thread waiter = new Thread(() -> {
            synchronized (lock) {
                try {
                    lock.wait();
                    System.out.println("not interrupted");
                }
                catch (InterruptedException e) {
                    System.out.println("wait interrupted");
                }
            }
        });
        waiter.start();
        Thread.sleep(5);
        waiter.interrupt();
        waiter.join();
        Thread poller = new Thread(() -> {
            long turns = 0;
            while (counter.get() < 100) {
                turns++;
            }
            System.out.println("polled " + (turns > 0));
        });
        poller.start();
        Thread.sleep(5);
        counter.set(100);
        poller.join();
    }

    // a timed wait that ends by its time, and one notified first
    static void timed() throws InterruptedException {
        synchronized (lock) {
            long before = System.nanoTime();
            lock.wait(20);
            System.out.println("timed out " + (System.nanoTime() - before >= 20_000_000L));
        }
        Thread notifier = new Thread(() -> {
            synchronized (lock) {
                flag = true;
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            notifier.start();
            while (!flag) {
                lock.wait(5000);
            }
            System.out.println("notified " + flag);
        }
        notifier.join();
    }

    // a daemon that never ends does not keep the program
    static void daemon() throws InterruptedException {
        Thread spinner = new Thread(() -> {
            while (true) {
                counter.incrementAndGet();
            }
        });
        spinner.setDaemon(true);
        spinner.start();
        Thread.sleep(5);
        System.out.println("daemon runs " + (counter.get() > 100));
    }

    public static void main(String[] args) throws Exception {
        heldAcrossSleep();
        System.out.println(cleanup(0));
        System.out.println(cleanup(1));
        System.out.println(cleanup(2));
        passing();
        leaving();
        pool();
        interrupts();
        timed();
        daemon();
    }
}
