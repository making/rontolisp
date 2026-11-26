// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.logging;

import java.io.PrintWriter;
import java.io.StringWriter;

import rontolisp.internal.Dates;

// Two lines, as a JDK 25 writes them where no format is configured (none can be here): the
// time in the host's zone at the record's instant, in en_US and the Gregorian calendar of
// every day, then the source -- the class and the method, the class alone, or the
// logger's name where no class is known --; then the level, the message and the trace of
// what was thrown, on a line of its own.
public class SimpleFormatter extends Formatter {

    private static final String[] MONTHS = { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct",
            "Nov", "Dec" };

    public SimpleFormatter() {
    }

    public String format(LogRecord record) {
        long[] at = Dates.gregorianFields(record.getMillis());
        StringBuilder line = new StringBuilder();
        String year = String.valueOf(at[0]);
        line.append(MONTHS[(int) at[1] - 1]).append(' ').append(Dates.two(at[2])).append(", ");
        for (int i = year.length(); i < 4; i++) {
            line.append('0');
        }
        long hour = at[4] % 12;
        line.append(year)
            .append(' ')
            .append(hour == 0 ? 12 : hour)
            .append(':')
            .append(Dates.two(at[5]))
            .append(':')
            .append(Dates.two(at[6]))
            .append(at[4] < 12 ? " AM " : " PM ");
        String sourceClass = record.getSourceClassName();
        if (sourceClass != null) {
            line.append(sourceClass);
            String sourceMethod = record.getSourceMethodName();
            if (sourceMethod != null) {
                line.append(' ').append(sourceMethod);
            }
        }
        else {
            line.append(record.getLoggerName());
        }
        String separator = System.lineSeparator();
        line.append(separator).append(record.getLevel().getLocalizedName()).append(": ").append(formatMessage(record));
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            StringWriter trace = new StringWriter();
            PrintWriter writer = new PrintWriter(trace);
            writer.println();
            thrown.printStackTrace(writer);
            writer.close();
            line.append(trace);
        }
        return line.append(separator).toString();
    }

}
