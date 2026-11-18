import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.function.*;

public class Stamped {
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
        StampedLock lock = new StampedLock();
        System.out.println(shown(lock) + " " + lock.isReadLocked() + " " + lock.isWriteLocked() + " " + lock.getReadLockCount());
        long o1 = lock.tryOptimisticRead();
        System.out.println("optimistic " + o1 + " " + lock.validate(o1) + " " + lock.validate(0) + " " + lock.validate(12345));
        long w1 = lock.writeLock();
        System.out.println("write " + w1 + " " + shown(lock) + " " + lock.isWriteLocked() + " " + lock.validate(o1) + " " + lock.tryOptimisticRead() + " " + lock.tryReadLock() + " " + lock.tryWriteLock() + " " + lock.validate(w1));
        lock.unlockWrite(w1);
        System.out.println(lock.validate(o1) + " " + lock.validate(w1) + " " + shown(lock));
        long o2 = lock.tryOptimisticRead();
        long r1 = lock.readLock();
        long r2 = lock.readLock();
        System.out.println("read " + o2 + " " + r1 + " " + r2 + " " + shown(lock) + " " + lock.getReadLockCount() + " " + lock.isReadLocked() + " " + lock.validate(o2) + " " + lock.validate(r1) + " " + lock.tryWriteLock() + " " + lock.tryOptimisticRead());
        lock.unlockRead(r1);
        System.out.println(shown(lock) + " " + lock.validate(r1) + " " + lock.validate(r2));
        lock.unlockRead(r2);
        System.out.println(shown(lock) + " " + lock.validate(o2) + " " + lock.tryOptimisticRead());
        // wrong stamps
        System.out.println(tried(() -> { lock.unlockWrite(w1); return "ok"; }));
        System.out.println(tried(() -> { lock.unlockRead(r1); return "ok"; }));
        System.out.println(tried(() -> { lock.unlock(0); return "ok"; }));
        long w2 = lock.writeLock();
        System.out.println("w2 " + w2);
        System.out.println(tried(() -> { lock.unlockWrite(w1); return "ok"; }));
        System.out.println(tried(() -> { lock.unlockRead(w2); return "ok"; }));
        System.out.println(tried(() -> { lock.unlockWrite(w2 + 1); return "ok"; }));
        lock.unlock(w2);
        long r3 = lock.readLock();
        System.out.println("r3 " + r3);
        System.out.println(tried(() -> { lock.unlockWrite(r3); return "ok"; }));
        System.out.println(tried(() -> { lock.unlockRead(r3 - 1); return "ok"; }));
        System.out.println(tried(() -> { lock.unlockRead(r3 + 300); return "ok"; }));
        System.out.println(tried(() -> { lock.unlock(r3 + 5); return "ok " + lock.getReadLockCount(); }));
        System.out.println(tried(() -> { lock.unlock(r3); return "ok"; }));
        System.out.println(lock.tryUnlockRead() + " " + lock.tryUnlockWrite());
        // conversions
        long r4 = lock.readLock();
        long c1 = lock.tryConvertToWriteLock(r4);
        System.out.println("convert " + r4 + " -> " + c1 + " " + lock.isWriteLocked() + " " + lock.isReadLocked());
        long c2 = lock.tryConvertToReadLock(c1);
        System.out.println(c1 + " -> " + c2 + " " + lock.isWriteLocked() + " " + lock.isReadLocked());
        long c3 = lock.tryConvertToOptimisticRead(c2);
        System.out.println(c2 + " -> " + c3 + " " + lock.isReadLocked() + " " + lock.validate(c3));
        long o3 = lock.tryOptimisticRead();
        long c4 = lock.tryConvertToWriteLock(o3);
        System.out.println(o3 + " -> " + c4 + " " + lock.isWriteLocked());
        long c5 = lock.tryConvertToWriteLock(c4);
        System.out.println(c4 + " -> " + c5);
        long c6 = lock.tryConvertToOptimisticRead(c5);
        System.out.println(c5 + " -> " + c6 + " " + lock.isWriteLocked());
        long o4 = lock.tryOptimisticRead();
        long c7 = lock.tryConvertToReadLock(o4);
        System.out.println(o4 + " -> " + c7 + " " + lock.getReadLockCount());
        long c8 = lock.tryConvertToReadLock(c7);
        long r5 = lock.readLock();
        long c9 = lock.tryConvertToWriteLock(c7);
        System.out.println(c7 + " -> " + c8 + " " + r5 + " " + c9 + " " + lock.getReadLockCount());
        long c10 = lock.tryConvertToOptimisticRead(r5);
        System.out.println(r5 + " -> " + c10 + " " + lock.getReadLockCount() + " " + lock.validate(c10));
        lock.unlockRead(c7);
        long stale = lock.tryOptimisticRead();
        long w3 = lock.writeLock();
        lock.unlockWrite(w3);
        System.out.println(stale + " " + lock.tryConvertToWriteLock(stale) + " " + lock.tryConvertToReadLock(stale) + " " + tried(() -> lock.tryConvertToOptimisticRead(stale)));
        System.out.println(tried(() -> lock.tryConvertToOptimisticRead(0)) + " " + lock.tryConvertToWriteLock(0) + " " + lock.tryConvertToReadLock(0));
        // statics
        System.out.println(StampedLock.isLockStamp(w3) + " " + StampedLock.isWriteLockStamp(w3) + " " + StampedLock.isReadLockStamp(w3) + " " + StampedLock.isOptimisticReadStamp(w3));
        System.out.println(StampedLock.isLockStamp(r5) + " " + StampedLock.isWriteLockStamp(r5) + " " + StampedLock.isReadLockStamp(r5) + " " + StampedLock.isOptimisticReadStamp(r5));
        System.out.println(StampedLock.isLockStamp(stale) + " " + StampedLock.isWriteLockStamp(stale) + " " + StampedLock.isReadLockStamp(stale) + " " + StampedLock.isOptimisticReadStamp(stale));
        System.out.println(StampedLock.isLockStamp(0) + " " + StampedLock.isWriteLockStamp(0) + " " + StampedLock.isReadLockStamp(0) + " " + StampedLock.isOptimisticReadStamp(0));
        // timed and interruptible
        long w4 = lock.tryWriteLock(10, TimeUnit.MILLISECONDS);
        System.out.println("timed " + w4 + " " + lock.tryReadLock(10, TimeUnit.MILLISECONDS) + " " + lock.tryWriteLock(10, TimeUnit.MILLISECONDS));
        lock.unlockWrite(w4);
        long r6 = lock.tryReadLock(10, TimeUnit.MILLISECONDS);
        System.out.println(r6 + " " + lock.tryWriteLock(10, TimeUnit.MILLISECONDS) + " " + lock.tryReadLock(10, TimeUnit.MILLISECONDS));
        lock.tryUnlockRead();
        lock.tryUnlockRead();
        System.out.println(shown(lock));
        long w5 = lock.writeLockInterruptibly();
        Thread waiter = new Thread(() -> {
            try {
                lock.readLockInterruptibly();
                System.out.println("not interrupted");
            }
            catch (InterruptedException e) {
                System.out.println("interrupted reading");
            }
            try {
                Thread.currentThread().interrupt();
                lock.writeLockInterruptibly();
            }
            catch (InterruptedException e) {
                System.out.println("interrupted writing " + Thread.currentThread().isInterrupted());
            }
            try {
                Thread.currentThread().interrupt();
                lock.tryWriteLock(1, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                System.out.println("interrupted trying");
            }
        });
        waiter.start();
        while (waiter.getState() != Thread.State.WAITING) Thread.sleep(1);
        waiter.interrupt();
        waiter.join();
        lock.unlockWrite(w5);
        // views
        Lock rl = lock.asReadLock();
        Lock wl = lock.asWriteLock();
        ReadWriteLock rw = lock.asReadWriteLock();
        rl.lock();
        System.out.println(shown(rl).replaceAll("\\$\\w+", "\\$View") + " " + lock.getReadLockCount() + " " + wl.tryLock() + " " + rw.readLock().tryLock() + " " + lock.getReadLockCount());
        rl.unlock();
        rw.readLock().unlock();
        System.out.println(tried(() -> { rl.unlock(); return "ok"; }));
        System.out.println(tried(() -> { wl.unlock(); return "ok"; }));
        wl.lock();
        System.out.println(lock.isWriteLocked() + " " + rl.tryLock() + " " + tried(() -> rl.newCondition()) + " " + tried(() -> wl.newCondition()));
        rw.writeLock().unlock();
        System.out.println(wl.tryLock(1, TimeUnit.MILLISECONDS) + " " + rl.tryLock(1, TimeUnit.MILLISECONDS));
        wl.unlock();
        System.out.println(shown(lock));
        // the point of it: a reader that validates
        StampedLock guard = new StampedLock();
        int[] pair = { 0, 0 };
        AtomicInteger torn = new AtomicInteger();
        AtomicInteger retried = new AtomicInteger();
        Thread writer = new Thread(() -> {
            for (int i = 1; i <= 2000; i++) {
                long stamp = guard.writeLock();
                try {
                    pair[0] = i;
                    pair[1] = -i;
                }
                finally {
                    guard.unlockWrite(stamp);
                }
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 2000; i++) {
                long stamp = guard.tryOptimisticRead();
                int a = pair[0];
                int b = pair[1];
                if (!guard.validate(stamp)) {
                    retried.incrementAndGet();
                    stamp = guard.readLock();
                    try {
                        a = pair[0];
                        b = pair[1];
                    }
                    finally {
                        guard.unlockRead(stamp);
                    }
                }
                if (a + b != 0) {
                    torn.incrementAndGet();
                }
            }
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        System.out.println("torn " + torn.get() + " " + pair[0] + " " + pair[1] + " " + shown(guard));
        // a writer waits for the readers, a reader for the writer
        StampedLock turns = new StampedLock();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        long held = turns.readLock();
        Thread wants = new Thread(() -> {
            long stamp = turns.writeLock();
            log.add("writer in");
            turns.unlockWrite(stamp);
        });
        wants.start();
        while (wants.getState() != Thread.State.WAITING) Thread.sleep(1);
        log.add("reader out");
        turns.unlockRead(held);
        wants.join();
        long holdW = turns.writeLock();
        Thread wantsRead = new Thread(() -> {
            long stamp = turns.readLock();
            log.add("reader in " + turns.getReadLockCount());
            turns.unlockRead(stamp);
        });
        wantsRead.start();
        while (wantsRead.getState() != Thread.State.WAITING) Thread.sleep(1);
        log.add("writer out");
        turns.unlockWrite(holdW);
        wantsRead.join();
        System.out.println(log);
    }
}
