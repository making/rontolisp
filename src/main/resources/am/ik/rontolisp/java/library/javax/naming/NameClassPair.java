// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.naming;

import java.io.Serializable;

// "(not relative)" before a name that is not relative; the name in the namespace is
// refused until it is set.
public class NameClassPair implements Serializable {

    private String name;

    private String className;

    private String fullName;

    private boolean isRel;

    public NameClassPair(String name, String className) {
        this(name, className, true);
    }

    public NameClassPair(String name, String className, boolean isRelative) {
        this.name = name;
        this.className = className;
        this.isRel = isRelative;
    }

    public String getClassName() {
        return className;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setClassName(String name) {
        this.className = name;
    }

    public boolean isRelative() {
        return isRel;
    }

    public void setRelative(boolean r) {
        isRel = r;
    }

    public String getNameInNamespace() {
        if (fullName == null) {
            throw new UnsupportedOperationException();
        }
        return fullName;
    }

    public void setNameInNamespace(String fullName) {
        this.fullName = fullName;
    }

    public String toString() {
        return (isRelative() ? "" : "(not relative)") + getName() + ": " + getClassName();
    }

}
