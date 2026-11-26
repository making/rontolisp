// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.naming;

import java.io.Serializable;

// An address of a type: two are equal where their types compare equal and their contents
// are equal, whatever their classes (a null type is refused as a JDK refuses it).
public abstract class RefAddr implements Serializable {

    protected String addrType;

    protected RefAddr(String addrType) {
        this.addrType = addrType;
    }

    public String getType() {
        return addrType;
    }

    public abstract Object getContent();

    public boolean equals(Object obj) {
        if (obj instanceof RefAddr other && addrType.compareTo(other.getType()) == 0) {
            Object content = getContent();
            Object theirs = other.getContent();
            return content == null ? theirs == null : content.equals(theirs);
        }
        return false;
    }

    public int hashCode() {
        Object content = getContent();
        return content == null ? addrType.hashCode() : addrType.hashCode() + content.hashCode();
    }

    public String toString() {
        return "Type: " + addrType + "\nContent: " + getContent() + "\n";
    }

}
