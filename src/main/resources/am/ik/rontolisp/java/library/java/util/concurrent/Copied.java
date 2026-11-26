// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.ArrayDeque;
import java.util.Iterator;

// The iterator of a queue held in an ArrayDeque under the queue's monitor: the elements
// there were when it was made, from either end, read from a copy. remove takes the
// element given last out of the queue, under the monitor, waking the threads waiting on
// it: the one at its place when nothing but this iterator changed the queue, else the
// first of its equals there (the last, walking back).
final class Copied<E> implements Iterator<E> {

    private final Object queue;

    private final ArrayDeque<E> held;

    // the copy's iterator, made under the queue's monitor
    private final Iterator<E> each;

    private final boolean backward;

    // the place in the queue of the element given last
    private int place;

    // the element given last; the iterator itself where there is none to remove
    private Object last = this;

    Copied(Object queue, ArrayDeque<E> held, Iterator<E> each, boolean backward) {
        this.queue = queue;
        this.held = held;
        this.each = each;
        this.backward = backward;
        this.place = backward ? held.size() : -1;
    }

    public boolean hasNext() {
        return each.hasNext();
    }

    public E next() {
        E e = each.next();
        place += backward ? -1 : 1;
        last = e;
        return e;
    }

    public void remove() {
        Object e = last;
        if (e == this) {
            throw new IllegalStateException();
        }
        last = this;
        synchronized (queue) {
            Iterator<E> walk = held.iterator();
            boolean done = false;
            for (int index = 0; walk.hasNext() && index <= place; index++) {
                if (walk.next() == e && index == place) {
                    walk.remove();
                    done = true;
                }
            }
            if (!done) {
                if (backward) {
                    held.removeLastOccurrence(e);
                }
                else {
                    held.removeFirstOccurrence(e);
                }
            }
            queue.notifyAll();
        }
        // walking forward, the next element is at the place this one left
        if (!backward) {
            place--;
        }
    }
}
