// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.logging;

// The standard error as it is when the handler is made, flushed after every record and
// never closed.
public class ConsoleHandler extends StreamHandler {

    public ConsoleHandler() {
        super(System.err, new SimpleFormatter());
    }

    public void publish(LogRecord record) {
        super.publish(record);
        flush();
    }

    public void close() {
        flush();
    }

}
