import java.util.concurrent.*;
import java.util.concurrent.locks.*;

public class Phase2 {
    public static void main(String[] args) throws Exception {
        Phaser full = new Phaser(65535);
        try { full.register(); } catch (Throwable t) { System.out.println(t.toString().replaceAll("@[0-9a-f]+", "@")); }
        try { full.bulkRegister(2); } catch (Throwable t) { System.out.println(t.toString().replaceAll("@[0-9a-f]+", "@")); }
        Phaser p = new Phaser(1) { protected boolean onAdvance(int phase, int parties) { throw new IllegalStateException("in onAdvance"); } };
        try { p.arrive(); } catch (Throwable t) { System.out.println(t); }
        System.out.println(p.toString().replaceAll("@[0-9a-f]+", "@") + " " + p.isTerminated());
        try { System.out.println(p.arrive()); } catch (Throwable t) { System.out.println(t.toString().replaceAll("@[0-9a-f]+", "@")); }
        // a tier's own onAdvance is not asked
        Phaser root = new Phaser();
        Phaser tier = new Phaser(root, 1) { protected boolean onAdvance(int phase, int parties) { System.out.println("tier asked"); return true; } };
        System.out.println(tier.arrive() + " " + tier.arrive() + " " + tier.getPhase() + " " + root.isTerminated());
        System.out.println(tier.arriveAndDeregister() + " " + root.isTerminated() + " " + tier.isTerminated() + " " + root + " " + tier.register());
        // StampedLock corners
        StampedLock lock = new StampedLock();
        long o = lock.tryOptimisticRead();
        System.out.println(o + " " + lock.tryConvertToOptimisticRead(o) + " " + lock.tryConvertToOptimisticRead(o + 1) + " " + lock.tryConvertToReadLock(o + 3) + " " + lock.getReadLockCount() + " " + lock.tryConvertToWriteLock(o + 2) + " " + lock.tryConvertToWriteLock(o + 128));
        long w = lock.writeLock();
        System.out.println(w + " " + lock.tryConvertToWriteLock(w + 1) + " " + lock.tryConvertToReadLock(w + 1) + " " + lock.tryConvertToOptimisticRead(w + 1) + " " + lock.validate(w + 1) + " " + lock.validate(w + 127) + " " + lock.isWriteLocked());
        try { lock.unlock(w + 1); System.out.println("unlocked"); } catch (Throwable t) { System.out.println(t); }
        try { lock.unlock(w); System.out.println("unlocked"); } catch (Throwable t) { System.out.println(t); }
        Thread.currentThread().interrupt();
        try { System.out.println(lock.writeLockInterruptibly()); } catch (InterruptedException e) { System.out.println("interrupted though free " + Thread.currentThread().isInterrupted()); }
        Thread.currentThread().interrupt();
        try { System.out.println(lock.tryWriteLock(0, TimeUnit.SECONDS)); } catch (InterruptedException e) { System.out.println("interrupted though free"); }
        Thread.currentThread().interrupt();
        try { System.out.println(lock.tryReadLock(1, TimeUnit.SECONDS)); } catch (InterruptedException e) { System.out.println("interrupted though free"); }
        Thread.currentThread().interrupt();
        try { System.out.println(lock.readLockInterruptibly()); } catch (InterruptedException e) { System.out.println("interrupted though free"); }
        Thread.currentThread().interrupt();
        System.out.println(lock.writeLock() + " " + Thread.interrupted() + " " + lock.tryUnlockWrite());
        Thread.currentThread().interrupt();
        try { System.out.println(lock.asWriteLock().tryLock(1, TimeUnit.SECONDS)); } catch (InterruptedException e) { System.out.println("interrupted though free"); }
        System.out.println(lock.toString().replaceAll("@[0-9a-f]+", "@"));
        try { lock.tryWriteLock(1, null); } catch (Throwable t) { System.out.println(t); }
    }
}
