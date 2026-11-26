// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file.attribute;

import java.io.IOException;
import java.util.Set;

public interface PosixFileAttributeView extends BasicFileAttributeView, FileOwnerAttributeView {

    String name();

    PosixFileAttributes readAttributes() throws IOException;

    void setPermissions(Set<PosixFilePermission> perms) throws IOException;

    void setGroup(GroupPrincipal group) throws IOException;
}
