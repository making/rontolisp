// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A thread is the host's where the target has threads (the interpreter, the JVM) and a
// body suspended and resumed where it has one stack (wasm): the native members are the
// run time's (java.lisp, "Threads"), everything a program can tell of a thread is
// written here over them.
package java.lang;

import java.util.concurrent.ThreadFactory;

public class Thread implements Runnable {

    /** What a thread is doing, as far as the library's own waits tell. */
    public enum State {
        NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, TERMINATED
    }

    @FunctionalInterface
    public interface UncaughtExceptionHandler {

        void uncaughtException(Thread t, Throwable e);
    }

    /** The values of a thread's InheritableThreadLocals, which a thread it makes takes over. */
    interface Inherited {

        Inherited child();
    }

    /** What runs a thread whose uncaught exception a handler is asked for. */
    private interface Guarding {

        void run(Thread thread);
    }

    /**
     * Made by a program that sets a handler, so a program that sets none holds no
     * catch of every exception.
     */
    private static final class Guard implements Guarding {

        public void run(Thread thread) {
            try {
                thread.run();
            }
            catch (Throwable thrown) {
                UncaughtExceptionHandler handler = thread.handler != null ? thread.handler : defaultHandler;
                try {
                    handler.uncaughtException(thread, thrown);
                }
                catch (Throwable dropped) {
                    // what a handler throws ends there, said as a JDK says it
                    System.err.println();
                    System.err.println("Exception: " + dropped.getClass().getName()
                            + " thrown from the UncaughtExceptionHandler in thread \"" + thread.name + "\"");
                }
            }
        }
    }

    private static volatile UncaughtExceptionHandler defaultHandler;

    private static volatile Guarding guarding;

    public static final int MIN_PRIORITY = 1;

    public static final int NORM_PRIORITY = 5;

    public static final int MAX_PRIORITY = 10;

    /** How many threads were made with no name: the number the next one is named by. */
    private static int unnamed;

    private static long identifiers = 1;

    private final Runnable target;

    /** The run time's record of the thread: what it parks on, whether it is interrupted. */
    private final Object carrier;

    private final long identifier;

    private final boolean virtual;

    private volatile String name;

    private boolean daemon;

    private int priority;

    private boolean started;

    private volatile boolean alive;

    /** 1 while the thread waits with no limit, 2 while it waits or sleeps with one. */
    private volatile int parked;

    private volatile UncaughtExceptionHandler handler;

    /** What each ThreadLocal holds for this thread, which alone reads it. */
    java.util.HashMap<ThreadLocal<?>, Object> locals;

    Inherited inherited;

    public Thread() {
        this(null, null, false, false);
    }

    public Thread(Runnable target) {
        this(target, null, false, false);
    }

    public Thread(String name) {
        this(null, named(name), false, false);
    }

    public Thread(Runnable target, String name) {
        this(target, named(name), false, false);
    }

    /**
     * A thread made by a program -- its name the next number's when none is given, a
     * daemon when the thread making it is one -- or the thread the program started on.
     */
    private Thread(Runnable target, String name, boolean virtual, boolean starting) {
        this.target = target;
        this.virtual = virtual;
        this.identifier = nextIdentifier();
        if (starting) {
            this.name = "main";
            this.priority = NORM_PRIORITY;
            this.started = true;
            this.alive = true;
            this.carrier = adopt0(this);
        }
        else {
            Thread maker = currentThread();
            this.name = name != null ? name : virtual ? "" : "Thread-" + nextNumber();
            this.daemon = virtual || maker.daemon;
            this.priority = virtual ? NORM_PRIORITY : maker.priority;
            this.carrier = carrier0(this);
            if (maker.inherited != null) {
                this.inherited = maker.inherited.child();
            }
        }
    }

    private static String named(String name) {
        if (name == null) {
            throw new NullPointerException("'name' is null");
        }
        return name;
    }

    private static synchronized int nextNumber() {
        return unnamed++;
    }

    private static synchronized long nextIdentifier() {
        return identifiers++;
    }

    public static Thread currentThread() {
        Thread current = current0();
        return current != null ? current : new Thread(null, null, false, true);
    }

    /** The name of the thread running: what a static initialization that threw keeps. */
    private static String currentName() {
        return currentThread().getName();
    }

    public static Thread startVirtualThread(Runnable task) {
        return ofVirtual().start(task);
    }

    public static Builder.OfPlatform ofPlatform() {
        return new Making(false);
    }

    public static Builder.OfVirtual ofVirtual() {
        return new Making(true);
    }

    public void run() {
        if (this.target != null) {
            this.target.run();
        }
    }

    public void start() {
        synchronized (this) {
            if (this.started) {
                throw new IllegalThreadStateException();
            }
            this.started = true;
            this.alive = true;
        }
        start0(this, this.carrier, this.daemon);
    }

    /** What a started thread runs: called by the run time on the thread itself. */
    private static void body(Thread thread) {
        Guarding guard = guarding;
        if (guard != null && (thread.handler != null || defaultHandler != null)) {
            guard.run(thread);
        }
        else {
            thread.run();
        }
    }

    public UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return this.handler;
    }

    public void setUncaughtExceptionHandler(UncaughtExceptionHandler handler) {
        this.handler = handler;
        guarding = new Guard();
    }

    public static UncaughtExceptionHandler getDefaultUncaughtExceptionHandler() {
        return defaultHandler;
    }

    public static void setDefaultUncaughtExceptionHandler(UncaughtExceptionHandler handler) {
        defaultHandler = handler;
        guarding = new Guard();
    }

    public State getState() {
        if (!this.started) {
            return State.NEW;
        }
        if (!this.alive) {
            return State.TERMINATED;
        }
        int waits = this.parked;
        return waits == 0 ? State.RUNNABLE : waits == 1 ? State.WAITING : State.TIMED_WAITING;
    }

    /** What a thread no handler of which took an exception writes. */
    private static void report(Thread thread, String thrown) {
        System.err.println("Exception in thread \"" + thread.name + "\" " + thrown);
    }

    /** A thread's end: the threads waiting in join() for it go on. */
    private static void finish(Thread thread) {
        synchronized (thread) {
            thread.alive = false;
            thread.notifyAll();
        }
    }

    public final void join() throws InterruptedException {
        join(0);
    }

    public final void join(long millis) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        synchronized (this) {
            if (millis == 0) {
                while (this.alive) {
                    wait(0);
                }
                return;
            }
            long deadline = System.nanoTime() + millis * 1000000L;
            while (this.alive) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return;
                }
                wait((left + 999999L) / 1000000L);
            }
        }
    }

    public static void sleep(long millis) throws InterruptedException {
        sleep(millis, 0);
    }

    public static void sleep(long millis, int nanos) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        if (nanos < 0 || nanos > 999999) {
            throw new IllegalArgumentException("nanosecond timeout value out of range");
        }
        Thread current = currentThread();
        current.parked = 2;
        boolean interrupted = sleep0(millis * 1000000L + nanos);
        current.parked = 0;
        if (interrupted) {
            throw new InterruptedException("sleep interrupted");
        }
    }

    public static void yield() {
        yield0();
    }

    public static void onSpinWait() {
        yield0();
    }

    public void interrupt() {
        interrupt0(this.carrier);
    }

    public boolean isInterrupted() {
        return interrupted0(this.carrier, false);
    }

    public static boolean interrupted() {
        return interrupted0(currentThread().carrier, true);
    }

    public static boolean holdsLock(Object object) {
        if (object == null) {
            throw new NullPointerException();
        }
        return holds0(object);
    }

    public final boolean isAlive() {
        return this.alive;
    }

    public final boolean isDaemon() {
        return this.daemon;
    }

    public final void setDaemon(boolean on) {
        if (this.virtual && !on) {
            throw new IllegalArgumentException("'false' not legal for virtual threads");
        }
        if (this.alive) {
            throw new IllegalThreadStateException();
        }
        this.daemon = on;
    }

    public final boolean isVirtual() {
        return this.virtual;
    }

    public final String getName() {
        return this.name;
    }

    public final void setName(String name) {
        this.name = named(name);
    }

    public final int getPriority() {
        return this.priority;
    }

    public final void setPriority(int priority) {
        if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
            throw new IllegalArgumentException();
        }
        if (!this.virtual) {
            this.priority = priority;
        }
    }

    public final long threadId() {
        return this.identifier;
    }

    public long getId() {
        return this.identifier;
    }

    public String toString() {
        return this.virtual ? "VirtualThread[#" + this.identifier + "," + this.name + "]"
                : "Thread[#" + this.identifier + "," + this.name + "," + this.priority + ",main]";
    }

    void keep(ThreadLocal<?> local, Object value) {
        if (this.locals == null) {
            this.locals = new java.util.HashMap<>();
        }
        this.locals.put(local, value);
    }

    /** Object.wait: what the run time answers, as the language's exceptions. */
    static void await(Object object, long millis) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        Thread current = currentThread();
        current.parked = millis == 0 ? 1 : 2;
        int ended = wait0(object, millis);
        current.parked = 0;
        if (ended == 1) {
            throw new InterruptedException();
        }
        if (ended == 2) {
            throw new IllegalMonitorStateException("current thread is not owner");
        }
    }

    /** Object.notify and notifyAll. */
    static void signal(Object object, boolean all) {
        if (!notify0(object, all)) {
            throw new IllegalMonitorStateException("current thread is not owner");
        }
    }

    private static native Object carrier0(Thread thread);

    private static native Object adopt0(Thread thread);

    private static native Thread current0();

    private static native void start0(Thread thread, Object carrier, boolean daemon);

    private static native boolean sleep0(long nanos);

    private static native void yield0();

    private static native void interrupt0(Object carrier);

    private static native boolean interrupted0(Object carrier, boolean clear);

    private static native boolean holds0(Object object);

    private static native int wait0(Object object, long millis);

    private static native boolean notify0(Object object, boolean all);

    public interface Builder {

        Builder name(String name);

        Builder name(String prefix, long start);

        Thread unstarted(Runnable task);

        Thread start(Runnable task);

        ThreadFactory factory();

        interface OfPlatform extends Builder {

            OfPlatform name(String name);

            OfPlatform name(String prefix, long start);

            OfPlatform daemon(boolean on);

            OfPlatform daemon();

            OfPlatform priority(int priority);
        }

        interface OfVirtual extends Builder {

            OfVirtual name(String name);

            OfVirtual name(String prefix, long start);
        }
    }

    /** What ofPlatform() and ofVirtual() answer: the threads it makes are named by it. */
    private static final class Making implements Builder.OfPlatform, Builder.OfVirtual {

        private final boolean virtual;

        private String name;

        private long counter = -1;

        private boolean daemonSet;

        private boolean daemon;

        private int priority;

        Making(boolean virtual) {
            this.virtual = virtual;
        }

        public Making name(String name) {
            this.name = named(name);
            this.counter = -1;
            return this;
        }

        public Making name(String prefix, long start) {
            if (start < 0) {
                throw new IllegalArgumentException("'start' is negative");
            }
            this.name = named(prefix);
            this.counter = start;
            return this;
        }

        public Making daemon(boolean on) {
            this.daemonSet = true;
            this.daemon = on;
            return this;
        }

        public Making daemon() {
            return daemon(true);
        }

        public Making priority(int priority) {
            if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
                throw new IllegalArgumentException();
            }
            this.priority = priority;
            return this;
        }

        public Thread unstarted(Runnable task) {
            if (task == null) {
                throw new NullPointerException();
            }
            String given = this.name == null ? (this.virtual ? "" : null)
                    : this.counter < 0 ? this.name : this.name + this.counter++;
            Thread made = new Thread(task, given, this.virtual, false);
            if (this.daemonSet) {
                made.daemon = this.daemon;
            }
            if (this.priority != 0) {
                made.priority = this.priority;
            }
            return made;
        }

        public Thread start(Runnable task) {
            Thread made = unstarted(task);
            made.start();
            return made;
        }

        public ThreadFactory factory() {
            return (Runnable task) -> unstarted(task);
        }
    }
}
