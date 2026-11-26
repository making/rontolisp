// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.security;

import java.io.Serializable;

// A name, "*" or one ending in ".*" naming every longer name under it; the actions are
// not kept. Only a permission of the same class is implied.
public abstract class BasicPermission extends Permission implements Serializable {

    public BasicPermission(String name) {
        super(checked(name));
    }

    public BasicPermission(String name, String actions) {
        super(checked(name));
    }

    private static String checked(String name) {
        if (name == null) {
            throw new NullPointerException("name can't be null");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name can't be empty");
        }
        return name;
    }

    public boolean implies(Permission p) {
        if (p == null || p.getClass() != getClass()) {
            return false;
        }
        String name = getName();
        String other = p.getName();
        int last = name.length() - 1;
        if (name.charAt(last) == '*' && (last == 0 || name.charAt(last - 1) == '.')) {
            return other.length() > last && other.startsWith(name.substring(0, last));
        }
        return name.equals(other);
    }

    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        return obj != null && obj.getClass() == getClass() && getName().equals(((Permission) obj).getName());
    }

    public int hashCode() {
        return getName().hashCode();
    }

    public String getActions() {
        return "";
    }

    public PermissionCollection newPermissionCollection() {
        return new BasicPermissionCollection(getClass());
    }

}
