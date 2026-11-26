// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.xml.transform;

public interface Source {

    void setSystemId(String systemId);

    String getSystemId();

    default boolean isEmpty() {
        throw new UnsupportedOperationException("The isEmpty method is not supported.");
    }

}
