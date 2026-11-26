// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file.attribute;

// An attribute a file is made with: its name, view:name, and its value.
public interface FileAttribute<T> {

    String name();

    T value();
}
