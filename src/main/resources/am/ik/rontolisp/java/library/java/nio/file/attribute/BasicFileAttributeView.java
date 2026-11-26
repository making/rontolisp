// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file.attribute;

import java.io.IOException;

public interface BasicFileAttributeView extends FileAttributeView {

    String name();

    BasicFileAttributes readAttributes() throws IOException;

    void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) throws IOException;
}
