// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import rontolisp.internal.Disk;

// The bytes of a file as they are asked for, with no buffer between the program and the
// host. A skip moves the position by what was asked, past the end and back too, as a
// JDK's does; a read there is at the end. A read at the end asked again reads what the
// file has grown by since. What is refused and its words are a JDK's: the path, then the
// reason in parentheses.
public class FileInputStream extends InputStream {

    // the file, null once closed
    private Object file;

    // whether the last read met the end
    private boolean ended;

    public FileInputStream(String name) throws FileNotFoundException {
        this(new File(name));
    }

    public FileInputStream(File file) throws FileNotFoundException {
        this.file = opened(file.getPath(), Disk.READ);
    }

    // The file a stream of the library reads or writes, or the refusal a JDK words.
    static Object opened(String name, int mode) throws FileNotFoundException {
        if (name.indexOf('\u0000') >= 0) {
            throw new FileNotFoundException("Invalid file path");
        }
        if (!name.isEmpty()) {
            if (Disk.kind(name) == Disk.DIRECTORY) {
                throw new FileNotFoundException(name + " (Is a directory)");
            }
            Object opened = Disk.opened(name, mode);
            if (opened != null) {
                return opened;
            }
        }
        throw new FileNotFoundException(name + " (" + Disk.words(Disk.refused(name, mode != Disk.READ)) + ")");
    }

    // The file to read, or null where a read is at the end.
    private Object reading() throws IOException {
        if (file == null) {
            throw new IOException("Stream Closed");
        }
        if (ended) {
            // a file read to its end is read again where it has grown since
            long at = Disk.position(file);
            if (Disk.length(file) <= at) {
                return null;
            }
            Disk.seek(file, at);
            ended = false;
        }
        return file;
    }

    public int read() throws IOException {
        Object from = reading();
        int b = from == null ? -1 : Disk.read(from);
        ended = b < 0;
        return b;
    }

    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        }
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        Object from = reading();
        if (len == 0) {
            return 0;
        }
        int n = from == null ? -1 : Disk.read(from, b, off, len);
        ended = n < 0;
        return n;
    }

    public long skip(long n) throws IOException {
        if (file == null) {
            throw new IOException("Stream Closed");
        }
        long to = Disk.position(file) + n;
        if (to < 0) {
            throw new IOException("Invalid argument");
        }
        Disk.seek(file, to);
        ended = false;
        return n;
    }

    public int available() throws IOException {
        if (file == null) {
            throw new IOException("Stream Closed");
        }
        long left = Disk.length(file) - Disk.position(file);
        return left <= 0 ? 0 : left > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) left;
    }

    public void close() throws IOException {
        Object closing = file;
        if (closing != null) {
            file = null;
            Disk.close(closing);
        }
    }
}
