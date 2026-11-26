// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.sql;

import java.util.EventListener;

public interface ConnectionEventListener extends EventListener {

    void connectionClosed(ConnectionEvent event);

    void connectionErrorOccurred(ConnectionEvent event);

}
