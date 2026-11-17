// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public abstract class AbstractSet<E> extends AbstractCollection<E> implements Set<E> {

    protected AbstractSet() {
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Set)) {
            return false;
        }
        Collection<?> other = (Collection<?>) o;
        return other.size() == size() && containsAll(other);
    }

    public int hashCode() {
        int hash = 0;
        for (E e : this) {
            if (e != null) {
                hash += e.hashCode();
            }
        }
        return hash;
    }
}
