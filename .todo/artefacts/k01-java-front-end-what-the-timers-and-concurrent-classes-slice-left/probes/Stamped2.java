import java.util.concurrent.locks.*;

public class Stamped2 {
    public static void main(String[] args) throws Exception {
        StampedLock lock = new StampedLock();
        long last = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 130; i++) {
            last = lock.readLock();
            if (i >= 124) sb.append(i + ":" + last + " ");
        }
        System.out.println(sb + " " + lock.getReadLockCount() + " " + lock + " " + lock.tryOptimisticRead() + " " + lock.validate(last) + " " + lock.tryReadLock());
        sb.setLength(0);
        for (int i = 131; i >= 122; i--) {
            lock.unlockRead(last);
            sb.append(lock.getReadLockCount() + ":" + lock.tryOptimisticRead() + ":" + lock.tryConvertToReadLock(lock.tryOptimisticRead()) + " ");
            lock.tryUnlockRead();
        }
        System.out.println(sb);
        System.out.println(lock.asReadLock().toString().replaceAll("@.*", "") + " " + lock.asWriteLock().toString().replaceAll("@.*", "") + " " + lock.asReadWriteLock().toString().replaceAll("@.*", ""));
        System.out.println(lock.asReadLock() == lock.asReadLock());
        System.out.println(lock.asReadWriteLock().readLock() == lock.asReadLock());
        // the version after very many writes is not probed
        StampedLock fresh = new StampedLock();
        long w = fresh.writeLock();
        System.out.println(fresh.tryConvertToReadLock(w) + " " + fresh.tryConvertToWriteLock(385) + " " + fresh.tryConvertToWriteLock(513));
        long o = fresh.tryOptimisticRead();
        System.out.println(o + " " + fresh.validate(o) + " " + fresh.validate(o + 1) + " " + fresh.validate(o + 127) + " " + fresh.validate(o + 128)+ " " + fresh.validate(o - 1));
        System.out.println(StampedLock.isOptimisticReadStamp(o + 1) + " " + StampedLock.isReadLockStamp(o) + " " + StampedLock.isReadLockStamp(127) + " " + StampedLock.isWriteLockStamp(128) + " " + StampedLock.isLockStamp(1) + " " + StampedLock.isOptimisticReadStamp(256)+ " " + StampedLock.isOptimisticReadStamp(-256) + " " + StampedLock.isLockStamp(-1) + " " + StampedLock.isWriteLockStamp(-128) + " " + StampedLock.isReadLockStamp(255) + " " + StampedLock.isWriteLockStamp(255));
        fresh.tryUnlockWrite();
        // unlock(stamp) and tryConvert with a wrong mode stamp
        long r = fresh.readLock();
        long rr = fresh.readLock();
        System.out.println(r + " " + rr + " " + fresh.tryConvertToWriteLock(r) + " " + fresh.tryConvertToOptimisticRead(rr) + " " + fresh.getReadLockCount() + " " + fresh.tryConvertToWriteLock(rr) + " " + fresh.isWriteLocked());
    }
}
