// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.naming;

// The root cause is the cause: getCause answers it, initCause sets it once as a
// Throwable's, setRootCause any number of times (itself ignored). A name given is kept as
// a clone of it. appendRemainingComponent is not here: with no remaining name it makes a
// CompositeName, which the subset does not have.
public class NamingException extends Exception {

    protected Name resolvedName;

    protected Object resolvedObj;

    protected Name remainingName;

    protected Throwable rootException;

    public NamingException(String explanation) {
        super(explanation);
    }

    public NamingException() {
    }

    public Name getResolvedName() {
        return resolvedName;
    }

    public Name getRemainingName() {
        return remainingName;
    }

    public Object getResolvedObj() {
        return resolvedObj;
    }

    public String getExplanation() {
        return getMessage();
    }

    public void setResolvedName(Name name) {
        resolvedName = name == null ? null : (Name) name.clone();
    }

    public void setRemainingName(Name name) {
        remainingName = name == null ? null : (Name) name.clone();
    }

    public void setResolvedObj(Object obj) {
        resolvedObj = obj;
    }

    public void appendRemainingName(Name name) {
        if (name == null) {
            return;
        }
        if (remainingName == null) {
            remainingName = (Name) name.clone();
            return;
        }
        try {
            remainingName.addAll(name);
        }
        catch (InvalidNameException e) {
            throw new IllegalArgumentException(e.toString());
        }
    }

    public Throwable getRootCause() {
        return rootException;
    }

    public void setRootCause(Throwable e) {
        if (e != this) {
            rootException = e;
        }
    }

    public Throwable getCause() {
        return getRootCause();
    }

    public Throwable initCause(Throwable cause) {
        super.initCause(cause);
        setRootCause(cause);
        return this;
    }

    public String toString() {
        String text = super.toString();
        if (rootException != null) {
            text += " [Root exception is " + rootException + "]";
        }
        if (remainingName != null) {
            text += "; remaining name '" + remainingName + "'";
        }
        return text;
    }

    public String toString(boolean detail) {
        String text = toString();
        return detail && resolvedObj != null ? text + "; resolved object " + resolvedObj : text;
    }

}
