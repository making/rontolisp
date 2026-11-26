// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

public enum RowIdLifetime {

    ROWID_UNSUPPORTED, ROWID_VALID_OTHER, ROWID_VALID_SESSION, ROWID_VALID_TRANSACTION, ROWID_VALID_FOREVER

}
