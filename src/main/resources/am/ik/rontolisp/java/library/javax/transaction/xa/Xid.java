// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.transaction.xa;

public interface Xid {

    int MAXGTRIDSIZE = 64;

    int MAXBQUALSIZE = 64;

    int getFormatId();

    byte[] getGlobalTransactionId();

    byte[] getBranchQualifier();

}
