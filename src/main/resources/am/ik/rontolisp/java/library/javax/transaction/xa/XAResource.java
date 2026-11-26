// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.transaction.xa;

public interface XAResource {

    int TMENDRSCAN = 8388608;

    int TMFAIL = 536870912;

    int TMJOIN = 2097152;

    int TMNOFLAGS = 0;

    int TMONEPHASE = 1073741824;

    int TMRESUME = 134217728;

    int TMSTARTRSCAN = 16777216;

    int TMSUCCESS = 67108864;

    int TMSUSPEND = 33554432;

    int XA_RDONLY = 3;

    int XA_OK = 0;

    void commit(Xid xid, boolean onePhase) throws XAException;

    void end(Xid xid, int flags) throws XAException;

    void forget(Xid xid) throws XAException;

    int getTransactionTimeout() throws XAException;

    boolean isSameRM(XAResource xares) throws XAException;

    int prepare(Xid xid) throws XAException;

    Xid[] recover(int flag) throws XAException;

    void rollback(Xid xid) throws XAException;

    boolean setTransactionTimeout(int seconds) throws XAException;

    void start(Xid xid, int flags) throws XAException;

}
