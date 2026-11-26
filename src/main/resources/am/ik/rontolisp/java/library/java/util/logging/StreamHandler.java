// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.logging;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.Objects;

// The records reach the stream through a writer, so they wait in its buffer until a
// flush, a close or a full buffer; the formatter's head goes ahead of the first, its tail
// is written at the close. What fails is the error manager's, with the step's code.
public class StreamHandler extends Handler {

    private OutputStream output;

    private boolean doneHeader;

    private volatile Writer writer;

    public StreamHandler() {
        setLevel(Level.INFO);
        setFormatter(new SimpleFormatter());
    }

    public StreamHandler(OutputStream out, Formatter formatter) {
        setLevel(Level.INFO);
        setFormatter(formatter);
        setOutputStream(out);
    }

    protected synchronized void setOutputStream(OutputStream out) throws SecurityException {
        Objects.requireNonNull(out);
        flushAndClose();
        output = out;
        doneHeader = false;
        writer = writerOf(out, getEncoding());
    }

    private static Writer writerOf(OutputStream out, String encoding) {
        if (encoding == null) {
            return new OutputStreamWriter(out);
        }
        try {
            return new OutputStreamWriter(out, encoding);
        }
        catch (UnsupportedEncodingException e) {
            // the encoding was checked where it was set
            throw new Error(e);
        }
    }

    public synchronized void setEncoding(String encoding) throws SecurityException, UnsupportedEncodingException {
        super.setEncoding(encoding);
        if (output == null) {
            return;
        }
        flush();
        writer = writerOf(output, encoding);
    }

    public synchronized void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }
        String msg;
        try {
            msg = getFormatter().format(record);
        }
        catch (Exception ex) {
            reportError(null, ex, ErrorManager.FORMAT_FAILURE);
            return;
        }
        try {
            if (!doneHeader) {
                writer.write(getFormatter().getHead(this));
                doneHeader = true;
            }
            writer.write(msg);
        }
        catch (Exception ex) {
            reportError(null, ex, ErrorManager.WRITE_FAILURE);
        }
    }

    public boolean isLoggable(LogRecord record) {
        if (writer == null || record == null) {
            return false;
        }
        return super.isLoggable(record);
    }

    public synchronized void flush() {
        if (writer != null) {
            try {
                writer.flush();
            }
            catch (Exception ex) {
                reportError(null, ex, ErrorManager.FLUSH_FAILURE);
            }
        }
    }

    private synchronized void flushAndClose() {
        if (writer != null) {
            try {
                if (!doneHeader) {
                    writer.write(getFormatter().getHead(this));
                    doneHeader = true;
                }
                writer.write(getFormatter().getTail(this));
                writer.flush();
                writer.close();
            }
            catch (Exception ex) {
                reportError(null, ex, ErrorManager.CLOSE_FAILURE);
            }
            writer = null;
            output = null;
        }
    }

    public synchronized void close() throws SecurityException {
        flushAndClose();
    }

}
