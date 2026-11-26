// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.io.File;
import java.util.ArrayList;

// What a program does at its end once it registered something for then: its shutdown
// hooks, started together and waited for, then its files to delete, the one registered
// first deleted last. Every end comes here -- main returning, the last thread that is no
// daemon ending, an uncaught exception, System.exit -- where the lowering finds a program
// reaching addHook or deleteOnExit (JavaLowering's entry, System.exit's intrinsic); a
// program reaching neither ends as it did. Runtime.halt ends at once.
public final class Shutdown {

    /** The hooks: made by the first, so a program that adds none starts no thread here. */
    private interface Hooks {

        void add(Thread hook);

        boolean remove(Thread hook);

        /** Starts every hook and waits until each has ended. */
        void run();

        /** What an end the program already is at does on the current thread: never returns. */
        void again();
    }

    private static final class Threads implements Hooks {

        private final ArrayList<Thread> hooks = new ArrayList<>();

        public void add(Thread hook) {
            if (hook.isAlive()) {
                throw new IllegalArgumentException("Hook already running");
            }
            if (this.hooks.contains(hook)) {
                throw new IllegalArgumentException("Hook previously registered");
            }
            this.hooks.add(hook);
        }

        public boolean remove(Thread hook) {
            if (hook == null) {
                throw new NullPointerException();
            }
            return this.hooks.remove(hook);
        }

        public void run() {
            for (Thread hook : this.hooks) {
                try {
                    hook.start();
                }
                catch (IllegalThreadStateException started) {
                    // a hook the program started itself is waited for, not started again
                }
            }
            for (Thread hook : this.hooks) {
                boolean waiting = true;
                while (waiting) {
                    try {
                        hook.join();
                        waiting = false;
                    }
                    catch (InterruptedException interrupted) {
                        // the end waits for its hooks whatever interrupts it
                    }
                }
            }
        }

        public void again() {
            if (this.hooks.contains(Thread.currentThread())) {
                // a JDK's hook waits here for ever, and so does the end waiting for it
                System.err.println("a shutdown hook called System.exit, which waits for ever: "
                        + "the experimental Java subset ends the program here");
                Runtime.getRuntime().halt(1);
            }
            Object never = new Object();
            synchronized (never) {
                while (true) {
                    try {
                        never.wait();
                    }
                    catch (InterruptedException interrupted) {
                        // an end under way is not left
                    }
                }
            }
        }
    }

    private static Hooks hooks;

    /** The paths to delete, in the order they were first registered. */
    private static ArrayList<String> files;

    private static boolean ending;

    private Shutdown() {
    }

    public static synchronized void addHook(Thread hook) {
        if (ending) {
            throw new IllegalStateException("Shutdown in progress");
        }
        if (hooks == null) {
            hooks = new Threads();
        }
        hooks.add(hook);
    }

    public static synchronized boolean removeHook(Thread hook) {
        if (ending) {
            throw new IllegalStateException("Shutdown in progress");
        }
        if (hooks == null) {
            if (hook == null) {
                throw new NullPointerException();
            }
            return false;
        }
        return hooks.remove(hook);
    }

    /** A file's path, deleted at the end: what a hook registers too. */
    public static synchronized void deleteOnExit(String path) {
        if (files == null) {
            files = new ArrayList<>();
        }
        if (!files.contains(path)) {
            files.add(path);
        }
    }

    private static synchronized boolean begin() {
        boolean first = !ending;
        ending = true;
        return first;
    }

    /** The end of a program that is no System.exit: once its threads that are no daemons ended. */
    public static void end() {
        if (begin()) {
            run();
        }
        else if (hooks != null) {
            hooks.again();
        }
    }

    /** System.exit, Runtime.exit: the end, then the status. */
    public static void exit(int status) {
        end();
        Runtime.getRuntime().halt(status);
    }

    private static void run() {
        if (hooks != null) {
            hooks.run();
        }
        ArrayList<String> deleted = files;
        if (deleted != null) {
            for (int i = deleted.size() - 1; i >= 0; i--) {
                new File(deleted.get(i)).delete();
            }
        }
    }
}
