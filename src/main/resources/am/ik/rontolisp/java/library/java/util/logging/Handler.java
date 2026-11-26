// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.logging;

import java.io.UnsupportedEncodingException;
import java.util.Objects;

// A handler passes every level and has no formatter until it is given one.
public abstract class Handler {

    private volatile Formatter formatter;

    private volatile Filter filter;

    private volatile Level logLevel = Level.ALL;

    private volatile ErrorManager errorManager = new ErrorManager();

    private volatile String encoding;

    protected Handler() {
    }

    public abstract void publish(LogRecord record);

    public abstract void flush();

    public abstract void close() throws SecurityException;

    public synchronized void setFormatter(Formatter newFormatter) throws SecurityException {
        formatter = Objects.requireNonNull(newFormatter);
    }

    public Formatter getFormatter() {
        return formatter;
    }

    public synchronized void setEncoding(String encoding) throws SecurityException, UnsupportedEncodingException {
        if (encoding != null) {
            rontolisp.internal.Support.charset(encoding);
        }
        this.encoding = encoding;
    }

    public String getEncoding() {
        return encoding;
    }

    public synchronized void setFilter(Filter newFilter) throws SecurityException {
        filter = newFilter;
    }

    public Filter getFilter() {
        return filter;
    }

    public synchronized void setErrorManager(ErrorManager em) {
        errorManager = Objects.requireNonNull(em);
    }

    public ErrorManager getErrorManager() {
        return errorManager;
    }

    protected void reportError(String msg, Exception ex, int code) {
        try {
            errorManager.error(msg, ex, code);
        }
        catch (Exception e) {
            // what the error manager itself fails at is dropped
        }
    }

    public synchronized void setLevel(Level newLevel) throws SecurityException {
        logLevel = Objects.requireNonNull(newLevel);
    }

    public Level getLevel() {
        return logLevel;
    }

    public boolean isLoggable(LogRecord record) {
        if (record == null) {
            return false;
        }
        int least = getLevel().intValue();
        if (record.getLevel().intValue() < least || least == Level.OFF.intValue()) {
            return false;
        }
        Filter theFilter = getFilter();
        return theFilter == null || theFilter.isLoggable(record);
    }

}
