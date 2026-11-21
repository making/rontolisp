// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.nio.charset.Charset;

// Characters written to a file: a FileOutputStream under an OutputStreamWriter, whose
// buffer reaches the file at flush() and close().
public class FileWriter extends OutputStreamWriter {

    public FileWriter(String fileName) throws IOException {
        super(new FileOutputStream(fileName));
    }

    public FileWriter(String fileName, boolean append) throws IOException {
        super(new FileOutputStream(fileName, append));
    }

    public FileWriter(File file) throws IOException {
        super(new FileOutputStream(file));
    }

    public FileWriter(File file, boolean append) throws IOException {
        super(new FileOutputStream(file, append));
    }

    public FileWriter(String fileName, Charset charset) throws IOException {
        super(new FileOutputStream(fileName), charset);
    }

    public FileWriter(String fileName, Charset charset, boolean append) throws IOException {
        super(new FileOutputStream(fileName, append), charset);
    }

    public FileWriter(File file, Charset charset) throws IOException {
        super(new FileOutputStream(file), charset);
    }

    public FileWriter(File file, Charset charset, boolean append) throws IOException {
        super(new FileOutputStream(file, append), charset);
    }
}
