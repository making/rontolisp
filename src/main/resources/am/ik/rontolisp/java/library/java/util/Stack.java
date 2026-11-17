// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public class Stack<E> extends Vector<E> {

    public Stack() {
    }

    public E push(E e) {
        add(e);
        return e;
    }

    public synchronized E pop() {
        if (isEmpty()) {
            throw new EmptyStackException();
        }
        return remove(size() - 1);
    }

    public synchronized E peek() {
        if (isEmpty()) {
            throw new EmptyStackException();
        }
        return get(size() - 1);
    }

    public boolean empty() {
        return isEmpty();
    }

    public synchronized int search(Object o) {
        int index = lastIndexOf(o);
        return index < 0 ? -1 : size() - index;
    }
}
