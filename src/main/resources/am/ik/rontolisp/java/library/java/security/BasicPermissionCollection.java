// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.security;

import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;

// The permissions of the one class that made the collection, by name, in the order of a
// ConcurrentHashMap, which is what a JDK 25 shows (its table grows at the twelfth name).
final class BasicPermissionCollection extends PermissionCollection {

    private final Class<?> type;

    private final ConcurrentHashMap<String, Permission> permissions = new ConcurrentHashMap<>();

    BasicPermissionCollection(Class<?> type) {
        this.type = type;
    }

    public void add(Permission permission) {
        if (permission == null || permission.getClass() != type) {
            throw new IllegalArgumentException("invalid permission: " + permission);
        }
        if (isReadOnly()) {
            throw new SecurityException("attempt to add a Permission to a readonly PermissionCollection");
        }
        permissions.put(permission.getName(), permission);
    }

    public boolean implies(Permission permission) {
        if (permission == null || permission.getClass() != type) {
            return false;
        }
        for (Permission each : permissions.values()) {
            if (each.implies(permission)) {
                return true;
            }
        }
        return false;
    }

    public Enumeration<Permission> elements() {
        return Collections.enumeration(permissions.values());
    }

}
