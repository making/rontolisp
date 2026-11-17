// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// What every collection has once it can count and enumerate its elements.
public abstract class AbstractCollection<E> implements Collection<E> {

    protected AbstractCollection() {
    }

    public abstract Iterator<E> iterator();

    public abstract int size();

    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean contains(Object o) {
        for (E e : this) {
            if (o == null ? e == null : o.equals(e)) {
                return true;
            }
        }
        return false;
    }

    public Object[] toArray() {
        Object[] out = new Object[size()];
        int i = 0;
        for (E e : this) {
            out[i++] = e;
        }
        return out;
    }

    public <T> T[] toArray(T[] a) {
        int n = size();
        T[] out = a.length >= n ? a : Arrays.newArray(a, n);
        Object[] target = out;
        int i = 0;
        for (E e : this) {
            target[i++] = e;
        }
        if (out.length > n) {
            target[n] = null;
        }
        return out;
    }

    public boolean add(E e) {
        throw new UnsupportedOperationException();
    }

    public boolean remove(Object o) {
        Iterator<E> each = iterator();
        while (each.hasNext()) {
            E e = each.next();
            if (o == null ? e == null : o.equals(e)) {
                each.remove();
                return true;
            }
        }
        return false;
    }

    public boolean containsAll(Collection<?> c) {
        for (Object o : c) {
            if (!contains(o)) {
                return false;
            }
        }
        return true;
    }

    public boolean addAll(Collection<? extends E> c) {
        boolean changed = false;
        for (E e : c) {
            if (add(e)) {
                changed = true;
            }
        }
        return changed;
    }

    public boolean removeAll(Collection<?> c) {
        boolean changed = false;
        Iterator<E> each = iterator();
        while (each.hasNext()) {
            if (c.contains(each.next())) {
                each.remove();
                changed = true;
            }
        }
        return changed;
    }

    public boolean retainAll(Collection<?> c) {
        boolean changed = false;
        Iterator<E> each = iterator();
        while (each.hasNext()) {
            if (!c.contains(each.next())) {
                each.remove();
                changed = true;
            }
        }
        return changed;
    }

    public void clear() {
        Iterator<E> each = iterator();
        while (each.hasNext()) {
            each.next();
            each.remove();
        }
    }

    public String toString() {
        StringBuilder text = new StringBuilder();
        text.append('[');
        boolean first = true;
        for (E e : this) {
            if (!first) {
                text.append(", ");
            }
            first = false;
            if (e == this) {
                text.append("(this Collection)");
            }
            else {
                text.append(e);
            }
        }
        return text.append(']').toString();
    }
}
