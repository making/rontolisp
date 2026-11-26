// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.logging;

public abstract class Formatter {

    protected Formatter() {
    }

    public abstract String format(LogRecord record);

    public String getHead(Handler h) {
        return "";
    }

    public String getTail(Handler h) {
        return "";
    }

    public String formatMessage(LogRecord record) {
        return record.formatted();
    }

}
