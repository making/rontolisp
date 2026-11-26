// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributes;

public interface FileVisitor<T> {

    FileVisitResult preVisitDirectory(T dir, BasicFileAttributes attrs) throws IOException;

    FileVisitResult visitFile(T file, BasicFileAttributes attrs) throws IOException;

    FileVisitResult visitFileFailed(T file, IOException exc) throws IOException;

    FileVisitResult postVisitDirectory(T dir, IOException exc) throws IOException;
}
