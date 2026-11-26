// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.security;

import java.io.Serializable;
import java.util.Collections;
import java.util.Enumeration;
import java.util.stream.Stream;

public abstract class PermissionCollection implements Serializable {

    private boolean readOnly;

    public PermissionCollection() {
    }

    public abstract void add(Permission permission);

    public abstract boolean implies(Permission permission);

    public abstract Enumeration<Permission> elements();

    public Stream<Permission> elementsAsStream() {
        return Collections.list(elements()).stream();
    }

    public void setReadOnly() {
        readOnly = true;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    // The object's own text, then a line of each permission and the parenthesis closing
    // them, each line ended.
    public String toString() {
        StringBuilder text = new StringBuilder(super.toString()).append(" (\n");
        for (Enumeration<Permission> each = elements(); each.hasMoreElements();) {
            text.append(' ').append(each.nextElement()).append('\n');
        }
        return text.append(")\n").toString();
    }

}
