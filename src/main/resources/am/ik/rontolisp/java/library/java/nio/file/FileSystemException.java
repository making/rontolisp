// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file;

import java.io.IOException;

// The file an operation failed on, the other file of an operation on two and the reason,
// a message made of the ones there are: "file -> other: reason".
public class FileSystemException extends IOException {

    private final String file;

    private final String other;

    public FileSystemException(String file) {
        super((String) null);
        this.file = file;
        this.other = null;
    }

    public FileSystemException(String file, String other, String reason) {
        super(reason);
        this.file = file;
        this.other = other;
    }

    public String getFile() {
        return file;
    }

    public String getOtherFile() {
        return other;
    }

    public String getReason() {
        return super.getMessage();
    }

    public String getMessage() {
        String reason = getReason();
        if (file == null && other == null) {
            return reason;
        }
        StringBuilder message = new StringBuilder();
        if (file != null) {
            message.append(file);
        }
        if (other != null) {
            message.append(" -> ").append(other);
        }
        if (reason != null) {
            message.append(": ").append(reason);
        }
        return message.toString();
    }
}
