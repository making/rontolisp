// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.transaction.xa;

public class XAException extends Exception {

    public int errorCode;

    public static final int XA_RBBASE = 100;

    public static final int XA_RBROLLBACK = 100;

    public static final int XA_RBCOMMFAIL = 101;

    public static final int XA_RBDEADLOCK = 102;

    public static final int XA_RBINTEGRITY = 103;

    public static final int XA_RBOTHER = 104;

    public static final int XA_RBPROTO = 105;

    public static final int XA_RBTIMEOUT = 106;

    public static final int XA_RBTRANSIENT = 107;

    public static final int XA_RBEND = 107;

    public static final int XA_NOMIGRATE = 9;

    public static final int XA_HEURHAZ = 8;

    public static final int XA_HEURCOM = 7;

    public static final int XA_HEURRB = 6;

    public static final int XA_HEURMIX = 5;

    public static final int XA_RETRY = 4;

    public static final int XA_RDONLY = 3;

    public static final int XAER_ASYNC = -2;

    public static final int XAER_RMERR = -3;

    public static final int XAER_NOTA = -4;

    public static final int XAER_INVAL = -5;

    public static final int XAER_PROTO = -6;

    public static final int XAER_RMFAIL = -7;

    public static final int XAER_DUPID = -8;

    public static final int XAER_OUTSIDE = -9;

    public XAException() {
    }

    public XAException(String s) {
        super(s);
    }

    public XAException(int errcode) {
        errorCode = errcode;
    }

}
