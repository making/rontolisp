// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.security;

import java.io.Serializable;

// A name and what a subclass says it implies. No permission is checked: a JDK 25 has no
// security manager and refuses checkGuard in the words below.
public abstract class Permission implements Guard, Serializable {

    private final String name;

    public Permission(String name) {
        this.name = name;
    }

    public void checkGuard(Object object) throws SecurityException {
        throw new SecurityException("checking permissions is not supported");
    }

    public abstract boolean implies(Permission permission);

    public abstract boolean equals(Object obj);

    public abstract int hashCode();

    public final String getName() {
        return name;
    }

    public abstract String getActions();

    public PermissionCollection newPermissionCollection() {
        return null;
    }

    public String toString() {
        String actions = getActions();
        String head = "(\"" + getClass().getName() + "\" \"" + name + "\"";
        return actions == null || actions.isEmpty() ? head + ")" : head + " \"" + actions + "\")";
    }

}
