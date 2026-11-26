// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.sql;

import java.sql.SQLException;
import javax.transaction.xa.XAResource;

public interface XAConnection extends PooledConnection {

    XAResource getXAResource() throws SQLException;

}
