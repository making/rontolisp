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

    // The smaller of the two is walked: the other collection, removing each of its
    // elements, where this set is larger, else this set, keeping what it does not hold.
    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        boolean changed = false;
        if (size() > c.size()) {
            for (Object o : c) {
                if (remove(o)) {
                    changed = true;
                }
            }
            return changed;
        }
        Iterator<E> each = iterator();
        while (each.hasNext()) {
            if (c.contains(each.next())) {
                each.remove();
                changed = true;
            }
        }
        return changed;
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
