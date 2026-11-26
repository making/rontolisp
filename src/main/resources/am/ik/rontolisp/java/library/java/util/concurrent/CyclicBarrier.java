// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A point so many threads wait at until the last of them arrives, again and again.
// The round the threads wait in is an object of its own, under the barrier's monitor:
// the last thread to arrive runs the barrier's action -- with the monitor given up --
// and ends the round; a thread that leaves early -- interrupted, its time up, the
// action thrown -- breaks it, and every thread of a broken round is told so.
package java.util.concurrent;

public class CyclicBarrier {

    private static final class Round {

        boolean ended;

        boolean broken;
    }

    private final int parties;

    private final Runnable action;

    private Round round = new Round();

    /** How many threads wait in the round. */
    private int arrived;

    /** The last thread of the round runs the barrier's action. */
    private boolean ending;

    public CyclicBarrier(int parties) {
        this(parties, null);
    }

    public CyclicBarrier(int parties, Runnable barrierAction) {
        if (parties <= 0) {
            throw new IllegalArgumentException();
        }
        this.parties = parties;
        this.action = barrierAction;
    }

    public int getParties() {
        return this.parties;
    }

    public synchronized int getNumberWaiting() {
        return this.arrived;
    }

    public synchronized boolean isBroken() {
        return this.round.broken;
    }

    /** The threads waiting are told the barrier is broken; the next round is whole. */
    public synchronized void reset() {
        this.round.broken = true;
        this.round = new Round();
        this.arrived = 0;
        notifyAll();
    }

    private void fail() {
        this.round.broken = true;
        this.arrived = 0;
        notifyAll();
    }

    public int await() throws InterruptedException, BrokenBarrierException {
        try {
            return arrive(-1L);
        }
        catch (TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    public int await(long timeout, TimeUnit unit) throws InterruptedException, BrokenBarrierException,
            TimeoutException {
        long nanos = unit.toNanos(timeout);
        return arrive(nanos < 0 ? 0 : nanos);
    }

    /** How many threads were still to arrive: the last one's answer is 0. */
    private int arrive(long nanos) throws InterruptedException, BrokenBarrierException, TimeoutException {
        Round mine;
        synchronized (this) {
            while (this.ending) {
                wait();
            }
            mine = this.round;
            if (mine.broken) {
                throw new BrokenBarrierException();
            }
            if (Thread.interrupted()) {
                fail();
                throw new InterruptedException();
            }
            int index = this.parties - ++this.arrived;
            if (index > 0) {
                return await(mine, index, nanos);
            }
            this.ending = true;
        }
        // the action runs with the monitor given up: a thread arriving meanwhile waits
        // for the round's end, and no monitor is held across what the action waits for
        boolean ran = false;
        try {
            if (this.action != null) {
                this.action.run();
            }
            ran = true;
        }
        finally {
            synchronized (this) {
                this.ending = false;
                if (ran) {
                    mine.ended = true;
                    this.round = new Round();
                    this.arrived = 0;
                    notifyAll();
                }
                else {
                    fail();
                }
            }
        }
        return 0;
    }

    /** Waits for the round's end, under the monitor: the thread's place in the round. */
    private int await(Round mine, int index, long nanos) throws InterruptedException, BrokenBarrierException,
            TimeoutException {
        long deadline = System.nanoTime() + nanos;
        while (!mine.ended && !mine.broken) {
            try {
                if (nanos < 0) {
                    wait();
                }
                else {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        fail();
                        throw new TimeoutException();
                    }
                    wait((left + 999999L) / 1000000L);
                }
            }
            catch (InterruptedException e) {
                if (mine.ended || mine.broken) {
                    // the round was over first: the interrupt is the thread's, later
                    Thread.currentThread().interrupt();
                }
                else {
                    fail();
                    throw e;
                }
            }
        }
        if (mine.ended) {
            return index;
        }
        throw new BrokenBarrierException();
    }
}
