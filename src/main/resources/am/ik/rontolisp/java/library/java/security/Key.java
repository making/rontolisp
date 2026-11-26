// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.security;

import java.io.Serializable;

public interface Key extends Serializable {

    String getAlgorithm();

    String getFormat();

    byte[] getEncoded();
}
