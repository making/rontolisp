// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A barrier whose parties come and go: each phase ends when every registered party has
// arrived. A phaser made under another is a tier of one tree, which counts as one
// party of the phaser above it while it has parties of its own and arrives there when
// all of them have. The phase is the root's, and every count of the tree is read and
// written under the root's monitor, on which the threads waiting for a phase wait. The
// root's onAdvance runs with that monitor given up, on the thread whose arrival ended
// the phase; until it answers, a party that registers waits.
package java.util.concurrent;

public class Phaser {

    private static final int MOST = 65535;

    private final Phaser parent;

    private final Phaser root;

    /** The root's: the phase the tree is in, which no number below zero is. */
    private int phase;

    /** The root's: whether the tree has ended. */
    private boolean terminated;

    /** The root's: whether its onAdvance runs. */
    private boolean advancing;

    private int parties;

    /** The parties still to arrive in the phase the counts are of. */
    private int unarrived;

    /** A tier's: the phase of the root its counts are of. */
    private int seen;

    public Phaser() {
        this(null, 0);
    }

    public Phaser(int parties) {
        this(null, parties);
    }

    public Phaser(Phaser parent) {
        this(parent, 0);
    }

    public Phaser(Phaser parent, int parties) {
        if (parties < 0 || parties > MOST) {
            throw new IllegalArgumentException("Illegal number of parties");
        }
        this.parent = parent;
        this.root = parent == null ? this : parent.root;
        if (parent != null) {
            synchronized (this.root) {
                this.seen = this.root.phase;
            }
        }
        if (parties > 0) {
            enter(parties);
        }
    }

    /** What getPhase() answers, under the root's monitor. */
    private int number() {
        return this.root.terminated ? this.root.phase | Integer.MIN_VALUE : this.root.phase;
    }

    /** Brings a tier's counts to the root's phase, under the root's monitor. */
    private void current() {
        if (this.parent != null && this.seen != this.root.phase && !this.root.terminated) {
            this.seen = this.root.phase;
            this.unarrived = this.parties;
        }
    }

    private String refused(String what) {
        return what + " for " + this;
    }

    /**
     * Whether a party coming now belongs to the next phase, under the root's monitor:
     * the phase is ending, or every party here has arrived -- for a tier with none, every
     * party of the one above, where it would count again.
     */
    private boolean late() {
        current();
        return this.root.advancing
                || (this.parties > 0 ? this.unarrived == 0 : this.parent != null && this.parent.late());
    }

    /** Counts parties in, under the root's monitor: a tier with none becomes a party above. */
    private void counted(int count) {
        if (this.parties + count > MOST) {
            throw new IllegalStateException(refused("Attempt to register more than " + MOST + " parties"));
        }
        if (this.parties == 0 && this.parent != null) {
            this.parent.counted(1);
        }
        this.parties += count;
        this.unarrived += count;
    }

    /** Registers parties once no phase is ending: the phase they are registered in. */
    private int enter(int count) {
        boolean interrupted = false;
        int at;
        synchronized (this.root) {
            while (!this.root.terminated && late()) {
                try {
                    this.root.wait();
                }
                catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            at = number();
            if (!this.root.terminated) {
                counted(count);
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return at;
    }

    public int register() {
        return enter(1);
    }

    public int bulkRegister(int parties) {
        if (parties < 0) {
            throw new IllegalArgumentException();
        }
        return parties == 0 ? getPhase() : enter(parties);
    }

    /**
     * One party's arrival, under the root's monitor: whether it was the last of the
     * tree, so the phase ends.
     */
    private boolean arrived(boolean leaving) {
        current();
        if (this.unarrived == 0) {
            throw new IllegalStateException(refused("Attempted arrival of unregistered party"));
        }
        this.unarrived--;
        if (leaving) {
            this.parties--;
        }
        if (this.unarrived > 0) {
            return false;
        }
        if (this.parent != null) {
            return this.parent.arrived(this.parties == 0);
        }
        this.advancing = true;
        return true;
    }

    /** Ends the phase: asks the root whether the tree ends with it, outside the monitor. */
    private void advance() {
        Phaser top = this.root;
        int ended;
        int registered;
        synchronized (top) {
            ended = top.phase;
            registered = top.parties;
        }
        boolean asked = false;
        boolean ends = false;
        try {
            ends = top.onAdvance(ended, registered);
            asked = true;
        }
        finally {
            synchronized (top) {
                top.advancing = false;
                if (asked) {
                    top.phase = (ended + 1) & Integer.MAX_VALUE;
                    if (ends) {
                        top.terminated = true;
                    }
                    else {
                        top.unarrived = top.parties;
                    }
                }
                top.notifyAll();
            }
        }
    }

    /**
     * A party arrives: the phase it arrived in, below zero when the tree has ended. One
     * that goes on to wait for the next phase is answered that phase's number; when its
     * own arrival ended the phase that is the number after the one it arrived in, also
     * when the tree ended with it (a JDK's answer).
     */
    private int arriving(boolean leaving, boolean awaiting) {
        boolean interrupted = false;
        boolean last;
        int at;
        synchronized (this.root) {
            while (this.root.advancing && !this.root.terminated) {
                try {
                    this.root.wait();
                }
                catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            at = number();
            last = !this.root.terminated && arrived(leaving);
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (last) {
            advance();
            if (awaiting) {
                return (at + 1) & Integer.MAX_VALUE;
            }
        }
        return awaiting ? awaitAdvance(at) : at;
    }

    public int arrive() {
        return arriving(false, false);
    }

    public int arriveAndDeregister() {
        return arriving(true, false);
    }

    public int arriveAndAwaitAdvance() {
        return arriving(false, true);
    }

    public int awaitAdvance(int phase) {
        if (phase < 0) {
            return phase;
        }
        boolean interrupted = false;
        int at;
        synchronized (this.root) {
            while (!this.root.terminated && this.root.phase == phase) {
                try {
                    this.root.wait();
                }
                catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            at = number();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return at;
    }

    public int awaitAdvanceInterruptibly(int phase) throws InterruptedException {
        if (phase < 0) {
            return phase;
        }
        synchronized (this.root) {
            while (!this.root.terminated && this.root.phase == phase) {
                this.root.wait();
            }
            return number();
        }
    }

    public int awaitAdvanceInterruptibly(int phase, long timeout, TimeUnit unit) throws InterruptedException,
            TimeoutException {
        long deadline = Timing.deadline(timeout, unit);
        if (phase < 0) {
            return phase;
        }
        synchronized (this.root) {
            while (!this.root.terminated && this.root.phase == phase) {
                if (!Timing.await(this.root, deadline)) {
                    throw new TimeoutException();
                }
            }
            return number();
        }
    }

    public void forceTermination() {
        synchronized (this.root) {
            this.root.terminated = true;
            this.root.notifyAll();
        }
    }

    public final int getPhase() {
        synchronized (this.root) {
            return number();
        }
    }

    public int getRegisteredParties() {
        synchronized (this.root) {
            return this.parties;
        }
    }

    public int getArrivedParties() {
        synchronized (this.root) {
            current();
            return this.parties - this.unarrived;
        }
    }

    public int getUnarrivedParties() {
        synchronized (this.root) {
            current();
            return this.unarrived;
        }
    }

    public Phaser getParent() {
        return this.parent;
    }

    public Phaser getRoot() {
        return this.root;
    }

    public boolean isTerminated() {
        synchronized (this.root) {
            return this.root.terminated;
        }
    }

    /** Whether the tree ends with the phase: asked of the root alone. */
    protected boolean onAdvance(int phase, int registeredParties) {
        return registeredParties == 0;
    }

    public String toString() {
        synchronized (this.root) {
            current();
            return super.toString() + "[phase = " + number() + " parties = " + this.parties + " arrived = "
                    + (this.parties - this.unarrived) + "]";
        }
    }
}
