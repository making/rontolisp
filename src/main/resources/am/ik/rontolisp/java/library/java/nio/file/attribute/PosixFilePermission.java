// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file.attribute;

// The nine permission bits, the owner's, the group's and everybody else's, from 0400 to
// 01 in the order declared.
public enum PosixFilePermission {
    OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_READ, GROUP_WRITE, GROUP_EXECUTE, OTHERS_READ, OTHERS_WRITE,
    OTHERS_EXECUTE
}
