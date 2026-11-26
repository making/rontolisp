// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.sql;

import java.util.EventListener;

public interface StatementEventListener extends EventListener {

    void statementClosed(StatementEvent event);

    void statementErrorOccurred(StatementEvent event);

}
