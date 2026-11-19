// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.stream;

import java.util.Iterator;

public interface BaseStream<T, S extends BaseStream<T, S>> extends AutoCloseable {

    Iterator<T> iterator();

    boolean isParallel();

    S sequential();

    S parallel();

    S unordered();

    S onClose(Runnable handler);

    void close();
}
