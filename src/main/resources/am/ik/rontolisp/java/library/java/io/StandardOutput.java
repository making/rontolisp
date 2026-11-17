// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

// System.out and System.err as objects: a PrintStream whose characters go to the host's
// stream as they are. A byte written is a byte of UTF-8, and its character is written
// once its last byte is.
final class StandardOutput extends PrintStream {

    private static StandardOutput out;

    private static StandardOutput err;

    private final boolean error;

    // the character whose bytes are being written, and how many are still to come
    private int partial;

    private int missing;

    private StandardOutput(boolean error) {
        this.error = error;
    }

    static PrintStream out() {
        if (out == null) {
            out = new StandardOutput(false);
        }
        return out;
    }

    static PrintStream err() {
        if (err == null) {
            err = new StandardOutput(true);
        }
        return err;
    }

    private static native void write0(boolean error, String text);

    private static native void put0(boolean error, int character);

    private static native void flush0(boolean error);

    void text(String s) {
        write0(error, s);
    }

    void text(String s, boolean line) {
        write0(error, s);
        if (line) {
            put0(error, '\n');
        }
    }

    public void write(int b) {
        int unit = b & 0xff;
        if (missing > 0 && (unit & 0xc0) == 0x80) {
            partial = (partial << 6) | (unit & 0x3f);
            if (--missing == 0) {
                put0(error, partial);
            }
            return;
        }
        if (missing > 0) {
            missing = 0;
            put0(error, 0xfffd);
        }
        if (unit < 0x80) {
            put0(error, unit);
        }
        else if (unit >= 0xc2 && unit < 0xf5) {
            missing = unit >= 0xf0 ? 3 : unit >= 0xe0 ? 2 : 1;
            partial = unit & (unit >= 0xf0 ? 0x07 : unit >= 0xe0 ? 0x0f : 0x1f);
        }
        else {
            put0(error, 0xfffd);
        }
    }

    public void write(byte[] buf, int off, int len) {
        java.util.Objects.checkFromIndexSize(off, len, buf.length);
        if (missing == 0) {
            int whole = len;
            // a character the bytes end inside is left to write(int)
            for (int back = 1; back <= 3 && back <= len; back++) {
                int unit = buf[off + len - back] & 0xff;
                if (unit >= 0xc0) {
                    whole = (unit >= 0xf0 ? 4 : unit >= 0xe0 ? 3 : 2) > back ? len - back : len;
                    break;
                }
                if (unit < 0x80) {
                    break;
                }
            }
            write0(error, new String(buf, off, whole));
            for (int i = whole; i < len; i++) {
                write(buf[off + i]);
            }
            return;
        }
        for (int i = 0; i < len; i++) {
            write(buf[off + i]);
        }
    }

    public void flush() {
        flush0(error);
    }

    public void close() {
        flush0(error);
    }

    public boolean checkError() {
        flush0(error);
        return false;
    }
}
