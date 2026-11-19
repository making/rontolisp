package shim;

import java.util.Collection;
import java.util.HashSet;

// Stands where java.util.LinkedHashSet is named, for the measurement only: a HashSet.
public class LinkedHashSet<E> extends HashSet<E> {

    public LinkedHashSet() {
    }

    public LinkedHashSet(int capacity) {
        super(capacity);
    }

    public LinkedHashSet(Collection<? extends E> from) {
        super(from);
    }
}
