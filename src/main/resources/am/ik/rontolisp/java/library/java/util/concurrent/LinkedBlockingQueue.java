// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.Collection;

public class LinkedBlockingQueue<E> extends Bounded<E> {

    public LinkedBlockingQueue() {
        super(Integer.MAX_VALUE);
    }

    public LinkedBlockingQueue(int capacity) {
        super(capacity);
    }

    public LinkedBlockingQueue(Collection<? extends E> c) {
        super(Integer.MAX_VALUE);
        for (E e : c) {
            add(e);
        }
    }
}
