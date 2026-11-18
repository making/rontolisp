// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

// System.in: the host's standard input, a stream of characters, read as the bytes of
// their UTF-8. A read of several bytes or characters waits for the first and goes on
// while the host has the next at hand; where the host cannot say (wasm), to the end of
// the line -- so that a program reading lines does not wait for more than one.
final class StandardInput extends InputStream {

    private static StandardInput in;

    // the bytes of the character read last that no read has taken yet
    private final byte[] left = new byte[4];

    private int leftAt;

    private int leftEnd;

    // the low surrogate of a character outside the BMP whose high one was read, or -1
    private int low = -1;

    private StandardInput() {
    }

    static InputStream in() {
        if (in == null) {
            in = new StandardInput();
        }
        return in;
    }

    // The next character of the standard input, -1 at its end.
    private static native int read0();

    private static native boolean ready0();

    // Whether a read of several goes on after the byte or character read last.
    private static native boolean more0(int last);

    public int read() {
        if (leftAt < leftEnd) {
            return left[leftAt++] & 0xff;
        }
        int c = low >= 0 ? low : read0();
        low = -1;
        if (c < 0x80) {
            return c;
        }
        leftAt = 0;
        leftEnd = 0;
        if (c < 0x800) {
            left[leftEnd++] = (byte) (0x80 | (c & 0x3f));
            return 0xc0 | (c >> 6);
        }
        if (c < 0x10000) {
            left[leftEnd++] = (byte) (0x80 | ((c >> 6) & 0x3f));
            left[leftEnd++] = (byte) (0x80 | (c & 0x3f));
            return 0xe0 | (c >> 12);
        }
        left[leftEnd++] = (byte) (0x80 | ((c >> 12) & 0x3f));
        left[leftEnd++] = (byte) (0x80 | ((c >> 6) & 0x3f));
        left[leftEnd++] = (byte) (0x80 | (c & 0x3f));
        return 0xf0 | (c >> 18);
    }

    public int read(byte[] b, int off, int len) {
        java.util.Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        int count = 0;
        while (count < len) {
            int next = read();
            if (next < 0) {
                return count == 0 ? -1 : count;
            }
            b[off + count++] = (byte) next;
            if (leftAt >= leftEnd && !more0(next)) {
                break;
            }
        }
        return count;
    }

    public int available() {
        return leftEnd - leftAt > 0 ? leftEnd - leftAt : ready0() ? 1 : 0;
    }

    // Whether no part of a character waits to be read.
    boolean whole() {
        return low < 0 && leftAt >= leftEnd;
    }

    // The next line without the \n that ends it, null at the end.
    String line() {
        return line0();
    }

    private static native String line0();

    // The next UTF-16 unit, for a reader of characters: -1 at the end.
    int unit() {
        if (low >= 0) {
            int unit = low;
            low = -1;
            return unit;
        }
        if (leftAt < leftEnd) {
            // bytes of a character a read of bytes left: its characters are lost
            leftAt = leftEnd;
            return 0xfffd;
        }
        int c = read0();
        if (c < 0x10000) {
            return c;
        }
        low = 0xdc00 | ((c - 0x10000) & 0x3ff);
        return 0xd800 | ((c - 0x10000) >> 10);
    }

    int units(char[] cbuf, int off, int len) {
        int count = 0;
        while (count < len) {
            int next = unit();
            if (next < 0) {
                return count == 0 ? -1 : count;
            }
            cbuf[off + count++] = (char) next;
            if (low < 0 && !more0(next)) {
                break;
            }
        }
        return count;
    }
}
