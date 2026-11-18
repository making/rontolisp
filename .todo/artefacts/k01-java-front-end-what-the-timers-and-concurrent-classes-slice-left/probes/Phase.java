import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public class Phase {
    static String shown(Object o) {
        return String.valueOf(o).replaceAll("@[0-9a-f]+", "@");
    }

    static String tried(Supplier<Object> s) {
        try {
            return shown(s.get());
        }
        catch (Throwable t) {
            return "threw " + t;
        }
    }

    public static void main(String[] args) throws Exception {
        Phaser p = new Phaser();
        System.out.println(shown(p) + " " + p.getPhase() + " " + p.getRegisteredParties() + " " + p.getArrivedParties() + " " + p.getUnarrivedParties() + " " + p.isTerminated() + " " + p.getParent() + " " + (p.getRoot() == p));
        System.out.println(p.register() + " " + p.bulkRegister(2) + " " + shown(p));
        System.out.println(p.arrive() + " " + shown(p) + " " + p.getArrivedParties() + " " + p.getUnarrivedParties());
        System.out.println(p.arrive() + " " + p.arrive() + " " + shown(p));
        System.out.println(p.awaitAdvance(0) + " " + p.awaitAdvance(5) + " " + p.awaitAdvance(-1));
        System.out.println(p.arriveAndDeregister() + " " + shown(p));
        System.out.println(p.arriveAndDeregister() + " " + p.arriveAndDeregister() + " " + shown(p) + " " + p.isTerminated());
        System.out.println(p.getPhase() + " " + p.register() + " " + p.arrive() + " " + p.arriveAndDeregister() + " " + p.arriveAndAwaitAdvance() + " " + p.awaitAdvance(0) + " " + p.bulkRegister(3) + " " + p.getRegisteredParties());
        System.out.println(tried(() -> new Phaser(-1)) + " " + tried(() -> new Phaser().bulkRegister(-1)) + " " + new Phaser().bulkRegister(0) + " " + tried(() -> new Phaser(65536)) + " " + shown(new Phaser(65535)));
        Phaser q = new Phaser(1);
        System.out.println(tried(() -> { q.arrive(); return q.arrive(); }));
        System.out.println(shown(q));
        Phaser none = new Phaser();
        System.out.println(tried(() -> none.arrive()) + " " + tried(() -> none.arriveAndDeregister()) + " " + tried(() -> none.arriveAndAwaitAdvance()));
        // phases advance, onAdvance decides
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Phaser three = new Phaser(1) {
            protected boolean onAdvance(int phase, int registeredParties) {
                log.add("advance " + phase + " " + registeredParties + " " + getPhase() + " " + getArrivedParties() + " " + Thread.currentThread().getName().startsWith("worker"));
                return phase >= 2 || registeredParties == 0;
            }
        };
        Thread[] workers = new Thread[3];
        for (int i = 0; i < 3; i++) {
            int id = i;
            three.register();
            workers[i] = new Thread(() -> {
                while (!three.isTerminated()) {
                    int at = three.arriveAndAwaitAdvance();
                    if (at < 0) {
                        log.add("worker ends at " + at + " " + three.getPhase());
                    }
                }
            }, "worker-" + i);
            workers[i].start();
        }
        while (three.getArrivedParties() < 3) Thread.sleep(1);
        System.out.println(shown(three));
        int at = three.arriveAndAwaitAdvance();
        at = three.arriveAndAwaitAdvance();
        int last = three.arriveAndAwaitAdvance();
        for (Thread t : workers) t.join();
        System.out.println(at + " " + last + " " + log);
        System.out.println(shown(three) + " " + three.getPhase() + " " + (three.getPhase() + Integer.MIN_VALUE) + " " + three.register() + " " + three.arrive() + " " + three.arriveAndAwaitAdvance() + " " + three.awaitAdvance(0) + " " + three.arriveAndDeregister() + " " + three.getRegisteredParties() + " " + three.bulkRegister(2));
        // forceTermination wakes who waits
        Phaser forced = new Phaser(2);
        Thread waits = new Thread(() -> log.add("woken with " + forced.arriveAndAwaitAdvance()));
        log.clear();
        waits.start();
        while (forced.getArrivedParties() < 1) Thread.sleep(1);
        forced.forceTermination();
        waits.join();
        System.out.println(log + " " + shown(forced) + " " + forced.isTerminated() + " " + forced.getPhase());
        forced.forceTermination();
        // awaitAdvance of a party that is not registered; interruptibly; with a limit
        Phaser gate = new Phaser(1);
        log.clear();
        Thread watcher = new Thread(() -> log.add("watcher saw " + gate.awaitAdvance(0)));
        watcher.start();
        while (watcher.getState() != Thread.State.WAITING) Thread.sleep(1);
        System.out.println(gate.arrive());
        watcher.join();
        System.out.println(log + " " + tried(() -> { try { return gate.awaitAdvanceInterruptibly(1, 20, TimeUnit.MILLISECONDS); } catch (Exception e) { return "checked " + e; } }) + " " + tried(() -> { try { return gate.awaitAdvanceInterruptibly(0, 20, TimeUnit.MILLISECONDS); } catch (Exception e) { return "checked " + e; } }));
        Thread interrupted = new Thread(() -> {
            try {
                gate.awaitAdvanceInterruptibly(1);
            }
            catch (InterruptedException e) {
                log.add("interrupted " + Thread.currentThread().isInterrupted());
            }
            Thread.currentThread().interrupt();
            log.add("uninterruptible " + gate.awaitAdvance(0) + " " + Thread.currentThread().isInterrupted());
            try {
                gate.awaitAdvanceInterruptibly(0);
                log.add("no wait, no exception " + Thread.interrupted());
                Thread.currentThread().interrupt();
                gate.awaitAdvanceInterruptibly(1);
            }
            catch (InterruptedException e) {
                log.add("interrupted before the wait");
            }
        });
        log.clear();
        interrupted.start();
        while (interrupted.getState() != Thread.State.WAITING) Thread.sleep(1);
        interrupted.interrupt();
        interrupted.join();
        System.out.println(log);
        // an interrupted thread that waits in awaitAdvance goes on waiting and is interrupted after
        Thread stubborn = new Thread(() -> {
            int phase = gate.awaitAdvance(1);
            log.add("advanced to " + phase + " interrupted " + Thread.currentThread().isInterrupted());
        });
        log.clear();
        stubborn.start();
        while (stubborn.getState() != Thread.State.WAITING) Thread.sleep(1);
        stubborn.interrupt();
        Thread.sleep(20);
        System.out.println(stubborn.isAlive() + " " + gate.arrive());
        stubborn.join();
        System.out.println(log);
        // tiers
        Phaser root = new Phaser() {
            protected boolean onAdvance(int phase, int parties) {
                log.add("root advance " + phase + " " + parties);
                return false;
            }
        };
        log.clear();
        Phaser left = new Phaser(root, 2);
        Phaser right = new Phaser(root);
        System.out.println(shown(root) + " " + shown(left) + " " + shown(right) + " " + (left.getParent() == root) + " " + (left.getRoot() == root) + " " + right.getRegisteredParties());
        right.register();
        System.out.println(shown(root) + " " + shown(right));
        System.out.println(left.arrive() + " " + right.arrive() + " " + shown(root) + " " + shown(left) + " " + shown(right));
        System.out.println(left.arrive() + " " + shown(root) + " " + shown(left) + " " + shown(right) + " " + log);
        System.out.println(right.arriveAndDeregister() + " " + shown(root) + " " + shown(right));
        System.out.println(left.arriveAndDeregister() + " " + left.arriveAndDeregister() + " " + shown(root) + " " + shown(left) + " " + root.isTerminated() + " " + left.isTerminated() + " " + log);
        Phaser sub = new Phaser(new Phaser(1), 1);
        System.out.println(sub.getPhase() + " " + shown(sub.getParent()));
        sub.forceTermination();
        System.out.println(sub.isTerminated() + " " + sub.getParent().isTerminated() + " " + shown(sub));
    }
}
