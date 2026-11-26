// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.crypto;

import java.security.Key;
import javax.security.auth.Destroyable;

public interface SecretKey extends Key, Destroyable {
}
