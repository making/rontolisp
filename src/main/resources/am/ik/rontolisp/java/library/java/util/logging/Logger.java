// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.logging;

import java.util.Objects;
import java.util.ResourceBundle;
import java.util.function.Supplier;

// A logger of a name is made once and kept (LogManager); its parent is the logger of the
// nearest name above it. A level not set is the parent's, INFO above the root. A record
// goes to the logger's handlers, then to its parent's while each passes it on.
//
// Where a record was made: a JDK walks the stack for the frame calling the logger. Here
// the call tells it -- the front end makes every call of a method of this class that makes
// or is handed a record, from outside the class, call calledFrom with its class and its
// method once its arguments are evaluated --, and the method it calls takes what it was
// told before it runs anything of the program's (a supplier, a filter, a handler).
public class Logger {

    public static final String GLOBAL_LOGGER_NAME = "global";

    // ahead of global, which is made with it
    private static final Handler[] NONE = new Handler[0];

    @Deprecated
    public static final Logger global = new Logger(GLOBAL_LOGGER_NAME, null);

    private static String callerClass;

    private static String callerMethod;

    private final String name;

    private volatile Logger parent;

    private volatile Level level;

    // replaced, never changed: a record is published to the handlers it found
    private volatile Handler[] handlers = NONE;

    private volatile boolean useParentHandlers = true;

    private volatile Filter filter;

    // whether the root's handlers are still to be made: at the first that asks for them
    volatile boolean defaults;

    protected Logger(String name, String resourceBundleName) {
        if (resourceBundleName != null) {
            throw new UnsupportedOperationException(
                    "a logger's resource bundle is not supported by the experimental Java subset yet");
        }
        this.name = name;
    }

    /** Where the call of a method making a record stands; null where nothing told it. */
    static void calledFrom(String className, String methodName) {
        callerClass = className;
        callerMethod = methodName;
    }

    /**
     * The method told where the call stands, else the one given: what a logging facade's
     * implementation gives logp of a stack this library does not keep.
     */
    static String toldMethod(String sourceMethod) {
        return callerClass != null ? callerMethod : sourceMethod;
    }

    /** The record told where the call stands, which is then forgotten. */
    private static void tell(LogRecord record) {
        record.told(callerClass, callerMethod);
        callerClass = null;
        callerMethod = null;
    }

    public static Logger getGlobal() {
        LogManager.getLogManager();
        return global;
    }

    public static Logger getLogger(String name) {
        return LogManager.getLogManager().demandLogger(name);
    }

    public static Logger getAnonymousLogger() {
        Logger made = new Logger(null, null);
        made.parent = LogManager.getLogManager().getLogger("");
        return made;
    }

    public ResourceBundle getResourceBundle() {
        return null;
    }

    public String getResourceBundleName() {
        return null;
    }

    public void setFilter(Filter newFilter) {
        filter = newFilter;
    }

    public Filter getFilter() {
        return filter;
    }

    public void log(LogRecord record) {
        if (!isLoggable(record.getLevel())) {
            return;
        }
        tell(record);
        Filter theFilter = filter;
        if (theFilter != null && !theFilter.isLoggable(record)) {
            return;
        }
        Logger logger = this;
        while (logger != null) {
            for (Handler handler : logger.handlers()) {
                handler.publish(record);
            }
            if (!logger.useParentHandlers) {
                break;
            }
            logger = logger.parent;
        }
    }

    private void doLog(LogRecord record) {
        record.setLoggerName(name);
        tell(record);
        log(record);
    }

    public void log(Level level, String msg) {
        if (!isLoggable(level)) {
            return;
        }
        doLog(new LogRecord(level, msg));
    }

    public void log(Level level, Supplier<String> msgSupplier) {
        if (!isLoggable(level)) {
            return;
        }
        String className = callerClass;
        String methodName = callerMethod;
        String msg = msgSupplier.get();
        calledFrom(className, methodName);
        doLog(new LogRecord(level, msg));
    }

    public void log(Level level, String msg, Object param1) {
        if (!isLoggable(level)) {
            return;
        }
        LogRecord record = new LogRecord(level, msg);
        record.setParameters(new Object[] { param1 });
        doLog(record);
    }

    public void log(Level level, String msg, Object[] params) {
        if (!isLoggable(level)) {
            return;
        }
        LogRecord record = new LogRecord(level, msg);
        record.setParameters(params);
        doLog(record);
    }

    public void log(Level level, String msg, Throwable thrown) {
        if (!isLoggable(level)) {
            return;
        }
        LogRecord record = new LogRecord(level, msg);
        record.setThrown(thrown);
        doLog(record);
    }

    public void log(Level level, Throwable thrown, Supplier<String> msgSupplier) {
        if (!isLoggable(level)) {
            return;
        }
        String className = callerClass;
        String methodName = callerMethod;
        LogRecord record = new LogRecord(level, msgSupplier.get());
        calledFrom(className, methodName);
        record.setThrown(thrown);
        doLog(record);
    }

    private void doLog(LogRecord record, String sourceClass, String sourceMethod) {
        record.setSourceClassName(sourceClass);
        record.setSourceMethodName(sourceMethod);
        doLog(record);
    }

    public void logp(Level level, String sourceClass, String sourceMethod, String msg) {
        if (!isLoggable(level)) {
            return;
        }
        doLog(new LogRecord(level, msg), sourceClass, sourceMethod);
    }

    public void logp(Level level, String sourceClass, String sourceMethod, Supplier<String> msgSupplier) {
        if (!isLoggable(level)) {
            return;
        }
        doLog(new LogRecord(level, msgSupplier.get()), sourceClass, sourceMethod);
    }

    public void logp(Level level, String sourceClass, String sourceMethod, String msg, Object param1) {
        if (!isLoggable(level)) {
            return;
        }
        LogRecord record = new LogRecord(level, msg);
        record.setParameters(new Object[] { param1 });
        doLog(record, sourceClass, sourceMethod);
    }

    public void logp(Level level, String sourceClass, String sourceMethod, String msg, Object[] params) {
        if (!isLoggable(level)) {
            return;
        }
        LogRecord record = new LogRecord(level, msg);
        record.setParameters(params);
        doLog(record, sourceClass, sourceMethod);
    }

    public void logp(Level level, String sourceClass, String sourceMethod, String msg, Throwable thrown) {
        if (!isLoggable(level)) {
            return;
        }
        LogRecord record = new LogRecord(level, msg);
        record.setThrown(thrown);
        doLog(record, sourceClass, sourceMethod);
    }

    public void logp(Level level, String sourceClass, String sourceMethod, Throwable thrown,
            Supplier<String> msgSupplier) {
        if (!isLoggable(level)) {
            return;
        }
        LogRecord record = new LogRecord(level, msgSupplier.get());
        record.setThrown(thrown);
        doLog(record, sourceClass, sourceMethod);
    }

    public void entering(String sourceClass, String sourceMethod) {
        logp(Level.FINER, sourceClass, sourceMethod, "ENTRY");
    }

    public void entering(String sourceClass, String sourceMethod, Object param1) {
        logp(Level.FINER, sourceClass, sourceMethod, "ENTRY {0}", param1);
    }

    public void entering(String sourceClass, String sourceMethod, Object[] params) {
        String msg = "ENTRY";
        if (params != null) {
            StringBuilder written = new StringBuilder(msg);
            for (int i = 0; i < params.length; i++) {
                written.append(" {").append(i).append('}');
            }
            msg = written.toString();
        }
        logp(Level.FINER, sourceClass, sourceMethod, msg, params);
    }

    public void exiting(String sourceClass, String sourceMethod) {
        logp(Level.FINER, sourceClass, sourceMethod, "RETURN");
    }

    public void exiting(String sourceClass, String sourceMethod, Object result) {
        logp(Level.FINER, sourceClass, sourceMethod, "RETURN {0}", result);
    }

    public void throwing(String sourceClass, String sourceMethod, Throwable thrown) {
        logp(Level.FINER, sourceClass, sourceMethod, "THROW", thrown);
    }

    public void severe(String msg) {
        log(Level.SEVERE, msg);
    }

    public void warning(String msg) {
        log(Level.WARNING, msg);
    }

    public void info(String msg) {
        log(Level.INFO, msg);
    }

    public void config(String msg) {
        log(Level.CONFIG, msg);
    }

    public void fine(String msg) {
        log(Level.FINE, msg);
    }

    public void finer(String msg) {
        log(Level.FINER, msg);
    }

    public void finest(String msg) {
        log(Level.FINEST, msg);
    }

    public void severe(Supplier<String> msgSupplier) {
        log(Level.SEVERE, msgSupplier);
    }

    public void warning(Supplier<String> msgSupplier) {
        log(Level.WARNING, msgSupplier);
    }

    public void info(Supplier<String> msgSupplier) {
        log(Level.INFO, msgSupplier);
    }

    public void config(Supplier<String> msgSupplier) {
        log(Level.CONFIG, msgSupplier);
    }

    public void fine(Supplier<String> msgSupplier) {
        log(Level.FINE, msgSupplier);
    }

    public void finer(Supplier<String> msgSupplier) {
        log(Level.FINER, msgSupplier);
    }

    public void finest(Supplier<String> msgSupplier) {
        log(Level.FINEST, msgSupplier);
    }

    public void setLevel(Level newLevel) {
        level = newLevel;
    }

    public Level getLevel() {
        return level;
    }

    public boolean isLoggable(Level level) {
        int value = level.intValue();
        int least = Level.INFO.intValue();
        for (Logger logger = this; logger != null; logger = logger.parent) {
            Level set = logger.level;
            if (set != null) {
                least = set.intValue();
                break;
            }
        }
        return value >= least && least != Level.OFF.intValue();
    }

    public String getName() {
        return name;
    }

    public void addHandler(Handler handler) {
        Objects.requireNonNull(handler);
        synchronized (this) {
            Handler[] had = handlers();
            Handler[] now = new Handler[had.length + 1];
            System.arraycopy(had, 0, now, 0, had.length);
            now[had.length] = handler;
            handlers = now;
        }
    }

    public synchronized void removeHandler(Handler handler) {
        if (handler == null) {
            return;
        }
        Handler[] had = handlers();
        for (int i = 0; i < had.length; i++) {
            if (had[i] == handler) {
                Handler[] now = new Handler[had.length - 1];
                System.arraycopy(had, 0, now, 0, i);
                System.arraycopy(had, i + 1, now, i, had.length - i - 1);
                handlers = now;
                return;
            }
        }
    }

    public Handler[] getHandlers() {
        return handlers().clone();
    }

    /** The handlers, the root's made where none was asked for before. */
    private Handler[] handlers() {
        if (defaults) {
            synchronized (this) {
                if (defaults) {
                    defaults = false;
                    handlers = new Handler[] { LogManager.defaultHandler() };
                }
            }
        }
        return handlers;
    }

    /** Every handler taken off: the ones it had. */
    synchronized Handler[] removeHandlers() {
        Handler[] had = handlers;
        handlers = NONE;
        defaults = false;
        return had;
    }

    public void setUseParentHandlers(boolean useParentHandlers) {
        this.useParentHandlers = useParentHandlers;
    }

    public boolean getUseParentHandlers() {
        return useParentHandlers;
    }

    public Logger getParent() {
        return parent;
    }

    public void setParent(Logger parent) {
        this.parent = Objects.requireNonNull(parent);
    }

    /** The parent the logger of the nearest name above it gives. */
    void placedUnder(Logger parent) {
        this.parent = parent;
    }

}
