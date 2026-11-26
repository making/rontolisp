// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import rontolisp.internal.Disk;

// Bytes written to a file as they come, with no buffer between the program and the host:
// a write has reached the file when it answers, so that another stream reads it. The
// file is made, and emptied unless the stream appends; what is refused and its words are
// a JDK's.
public class FileOutputStream extends OutputStream {

    // the file, null once closed
    private Object file;

    public FileOutputStream(String name) throws FileNotFoundException {
        this(new File(name), false);
    }

    public FileOutputStream(String name, boolean append) throws FileNotFoundException {
        this(new File(name), append);
    }

    public FileOutputStream(File file) throws FileNotFoundException {
        this(file, false);
    }

    public FileOutputStream(File file, boolean append) throws FileNotFoundException {
        this.file = FileInputStream.opened(file.getPath(), append ? Disk.APPEND : Disk.WRITE);
    }

    private Object writing() throws IOException {
        if (file == null) {
            throw new IOException("Stream Closed");
        }
        return file;
    }

    public void write(int b) throws IOException {
        Disk.write(writing(), b & 0xff);
    }

    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        }
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        Object to = writing();
        if (len > 0) {
            Disk.write(to, b, off, len);
        }
    }

    public void close() throws IOException {
        Object closing = file;
        if (closing != null) {
            file = null;
            Disk.close(closing);
        }
    }
}
