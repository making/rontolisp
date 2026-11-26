// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.logging;

import java.io.PrintWriter;
import java.io.StringWriter;

// The root's handler where the build hands the records to its host's log (--wasi-logging:
// wasi:logging/logging on a component). A record is the host's entry of the level nearest
// its own, the logger's name its context and the text the formatter makes of it, a line's
// end at its end left off. It passes what the console passes, INFO and above, until it is
// told otherwise, so the build decides where the lines go and not which. A JDK has none.
final class HostHandler extends Handler {

    HostHandler() {
        setLevel(Level.INFO);
        setFormatter(new Text());
    }

    public void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }
        String text;
        try {
            text = getFormatter().format(record);
        }
        catch (Exception ex) {
            reportError(null, ex, ErrorManager.FORMAT_FAILURE);
            return;
        }
        if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        String context = record.getLoggerName();
        log0(severity(record.getLevel()), context == null ? "" : context, text);
    }

    public void flush() {
    }

    public void close() {
    }

    // wasi:logging's levels by their places: trace, debug, info, warn, error, critical.
    // FINER and FINEST trace, CONFIG and FINE debug, a level above SEVERE critical
    static int severity(Level level) {
        int value = level.intValue();
        return value > 1000 ? 5 : value >= 1000 ? 4 : value >= 900 ? 3 : value >= 800 ? 2 : value >= 500 ? 1 : 0;
    }

    // the host's log: the level by its place, the context and the message
    private static native void log0(int level, String context, String message);

    // the message as formatMessage makes it; where something was thrown, a line's end
    // and printStackTrace's text
    static final class Text extends Formatter {

        public String format(LogRecord record) {
            String message = formatMessage(record);
            Throwable thrown = record.getThrown();
            if (thrown == null) {
                return message;
            }
            StringWriter trace = new StringWriter();
            PrintWriter writer = new PrintWriter(trace);
            writer.println();
            thrown.printStackTrace(writer);
            writer.close();
            return message + trace;
        }

    }

}
