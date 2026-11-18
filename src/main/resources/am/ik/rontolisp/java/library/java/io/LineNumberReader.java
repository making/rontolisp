// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

// A buffered reader counting the lines it has passed: a line ends at \n, \r, \r\n, or
// at the end of the input when characters stand after the last of those.
public class LineNumberReader extends BufferedReader {

    private int lineNumber;

    // whether the character read last was a \r, so that a \n after it ends no line
    private boolean afterCarriageReturn;

    // whether characters were read since the last line's end, which the end of the
    // input then ends. Measured against a JDK 25: a skip leaves it false, and a mark
    // does not keep it.
    private boolean open;

    private int markedLineNumber;

    private boolean markedAfterCarriageReturn;

    public LineNumberReader(Reader in) {
        super(in);
    }

    public LineNumberReader(Reader in, int sz) {
        super(in, sz);
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    // The end of the input ends the line that was open.
    private void atEnd() {
        if (open) {
            lineNumber++;
            open = false;
        }
    }

    // A line's end is read as one \n.
    public int read() throws IOException {
        int c = super.read();
        if (afterCarriageReturn) {
            afterCarriageReturn = false;
            if (c == '\n') {
                c = super.read();
            }
        }
        if (c == '\r') {
            afterCarriageReturn = true;
            lineNumber++;
            open = false;
            return '\n';
        }
        if (c == '\n') {
            lineNumber++;
            open = false;
        }
        else if (c < 0) {
            atEnd();
        }
        else {
            open = true;
        }
        return c;
    }

    // The characters as they are; the lines among them are counted.
    public int read(char[] cbuf, int off, int len) throws IOException {
        int n = super.read(cbuf, off, len);
        if (n < 0) {
            atEnd();
        }
        for (int i = off; i < off + n; i++) {
            char c = cbuf[i];
            if (afterCarriageReturn) {
                afterCarriageReturn = false;
                if (c == '\n') {
                    continue;
                }
            }
            if (c == '\r') {
                afterCarriageReturn = true;
                lineNumber++;
                open = false;
            }
            else if (c == '\n') {
                lineNumber++;
                open = false;
            }
            else {
                open = true;
            }
        }
        return n;
    }

    public String readLine() throws IOException {
        if (afterCarriageReturn) {
            afterCarriageReturn();
            afterCarriageReturn = false;
        }
        String line = super.readLine();
        if (line != null) {
            lineNumber++;
            open = false;
        }
        else {
            atEnd();
        }
        return line;
    }

    public long skip(long n) throws IOException {
        if (n < 0) {
            throw new IllegalArgumentException("skip() value is negative");
        }
        char[] dropped = new char[n < 8192 ? (int) n : 8192];
        long left = n;
        while (left > 0) {
            int read = read(dropped, 0, left < dropped.length ? (int) left : dropped.length);
            if (read < 0) {
                break;
            }
            left -= read;
        }
        open = false;
        return n - left;
    }

    // A \n dropped after the mark is no character of the limit (measured: a mark of
    // one character after a \r holds over the \n and one more).
    public void mark(int readAheadLimit) throws IOException {
        super.mark(afterCarriageReturn ? readAheadLimit + 1 : readAheadLimit);
        markedLineNumber = lineNumber;
        markedAfterCarriageReturn = afterCarriageReturn;
    }

    public void reset() throws IOException {
        super.reset();
        lineNumber = markedLineNumber;
        afterCarriageReturn = markedAfterCarriageReturn;
    }
}
