package shim;

import java.util.ArrayDeque;
import java.util.Collection;

// Stands where java.util.LinkedList is named, for the measurement only: an ArrayDeque.
public class LinkedList<E> extends ArrayDeque<E> {

    public LinkedList() {
    }

    public LinkedList(Collection<? extends E> from) {
        super(from);
    }
}
