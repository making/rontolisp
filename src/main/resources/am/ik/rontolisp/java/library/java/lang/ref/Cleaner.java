// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.ref;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;

// Cleaning actions registered for objects. The collector of no target finds an object
// unreachable (Reference), so an action runs where the program calls clean(), once, on
// the thread calling it. A cleaner made with a thread factory asks it for its thread,
// makes it a daemon and starts it, as a JDK does; the thread waits, with nothing ever to
// run. One made with none starts no thread: no program can name it.
public final class Cleaner {

    private Cleaner() {
    }

    public static Cleaner create() {
        return new Cleaner();
    }

    public static Cleaner create(ThreadFactory threadFactory) {
        Objects.requireNonNull(threadFactory, "threadFactory");
        Cleaner made = new Cleaner();
        Thread thread = threadFactory.newThread(made::idle);
        thread.setDaemon(true);
        thread.start();
        return made;
    }

    // what the cleaner's thread runs: a wait no action ends
    private void idle() {
        synchronized (this) {
            while (true) {
                try {
                    wait(60000L);
                }
                catch (InterruptedException ex) {
                    // a cleaner's thread is not stopped by an interrupt
                }
            }
        }
    }

    public Cleanable register(Object obj, Runnable action) {
        Objects.requireNonNull(obj, "obj");
        Objects.requireNonNull(action, "action");
        return new Registered(action);
    }

    public interface Cleanable {

        void clean();
    }

    // an action not run yet, or null once it ran
    private static final class Registered implements Cleanable {

        private Runnable action;

        Registered(Runnable action) {
            this.action = action;
        }

        public void clean() {
            Runnable taken;
            synchronized (this) {
                taken = this.action;
                this.action = null;
            }
            if (taken != null) {
                taken.run();
            }
        }
    }
}
