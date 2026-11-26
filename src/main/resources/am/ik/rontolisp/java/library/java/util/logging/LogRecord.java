// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.logging;

import java.io.Serializable;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.Objects;
import java.util.ResourceBundle;

// What a record holds of where it was made is the class and the method of the call of a
// logger handing it on, as that call told the logger (Logger.calledFrom); a record no
// logger was handed, or one asked for its source first, has none, as a JDK's has none
// found on the stack. The sequence numbers count every record made, from 0.
public class LogRecord implements Serializable {

    private static long sequence;

    private Level level;

    private long sequenceNumber;

    private String sourceClassName;

    private String sourceMethodName;

    // whether the source is still to be told by the logger the record is handed to
    boolean untold = true;

    private String message;

    private long threadID;

    private long millis;

    // the nanoseconds past the millisecond
    private int nanos;

    private Throwable thrown;

    private String loggerName;

    // made only where a program can give parameters: a message's formatting, which
    // MessageFormat does, is in a program only then
    private Parameters parameters;

    static class Parameters {

        final Object[] values;

        Parameters(Object[] values) {
            this.values = values;
        }

        /**
         * The message with the parameters, as a MessageFormat writes them where there is
         * one and a brace followed by an ASCII digit; as it is where there is none or the
         * format refuses it (a JDK 25's answers).
         */
        String formatted(String format) {
            if (values.length == 0 || !placesArgument(format)) {
                return format;
            }
            try {
                return MessageFormat.format(format, values);
            }
            catch (UnsupportedOperationException e) {
                // what the subset cannot format yet is said, not hidden
                throw e;
            }
            catch (Exception e) {
                return format;
            }
        }

        private static boolean placesArgument(String format) {
            for (int i = format.indexOf('{'); i >= 0 && i + 1 < format.length(); i = format.indexOf('{', i + 1)) {
                char next = format.charAt(i + 1);
                if (next >= '0' && next <= '9') {
                    return true;
                }
            }
            return false;
        }

    }

    public LogRecord(Level level, String msg) {
        this.level = Objects.requireNonNull(level);
        this.message = msg;
        this.sequenceNumber = next();
        this.threadID = Thread.currentThread().threadId();
        this.millis = System.currentTimeMillis();
    }

    private static synchronized long next() {
        return sequence++;
    }

    /** Where the logger handed the record was called from, unless the record says itself. */
    void told(String className, String methodName) {
        if (untold) {
            untold = false;
            sourceClassName = className;
            sourceMethodName = methodName;
        }
    }

    public String getLoggerName() {
        return loggerName;
    }

    public void setLoggerName(String name) {
        loggerName = name;
    }

    public ResourceBundle getResourceBundle() {
        return null;
    }

    public String getResourceBundleName() {
        return null;
    }

    public Level getLevel() {
        return level;
    }

    public void setLevel(Level level) {
        this.level = Objects.requireNonNull(level);
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(long seq) {
        sequenceNumber = seq;
    }

    public String getSourceClassName() {
        untold = false;
        return sourceClassName;
    }

    public void setSourceClassName(String sourceClassName) {
        untold = false;
        this.sourceClassName = sourceClassName;
    }

    public String getSourceMethodName() {
        untold = false;
        return sourceMethodName;
    }

    public void setSourceMethodName(String sourceMethodName) {
        untold = false;
        this.sourceMethodName = sourceMethodName;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Object[] getParameters() {
        return parameters == null ? null : parameters.values;
    }

    public void setParameters(Object[] parameters) {
        this.parameters = parameters == null ? null : new Parameters(parameters);
    }

    /** The message with its parameters, if it has any. */
    String formatted() {
        Parameters given = parameters;
        return given == null || message == null ? message : given.formatted(message);
    }

    @Deprecated
    public int getThreadID() {
        return (int) threadID;
    }

    @Deprecated
    public void setThreadID(int threadID) {
        this.threadID = threadID;
    }

    public long getLongThreadID() {
        return threadID;
    }

    public LogRecord setLongThreadID(long longThreadID) {
        threadID = longThreadID;
        return this;
    }

    public long getMillis() {
        return millis;
    }

    @Deprecated
    public void setMillis(long millis) {
        this.millis = millis;
        nanos = 0;
    }

    public Instant getInstant() {
        return Instant.ofEpochMilli(millis).plusNanos(nanos);
    }

    public void setInstant(Instant instant) {
        long seconds = instant.getEpochSecond();
        millis = Math.addExact(Math.multiplyExact(seconds, 1000L), instant.getNano() / 1000000);
        nanos = instant.getNano() % 1000000;
    }

    public Throwable getThrown() {
        return thrown;
    }

    public void setThrown(Throwable thrown) {
        this.thrown = thrown;
    }

}
