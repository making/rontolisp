// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.Deque;

public interface BlockingDeque<E> extends BlockingQueue<E>, Deque<E> {

    void putFirst(E e) throws InterruptedException;

    void putLast(E e) throws InterruptedException;

    boolean offerFirst(E e, long timeout, TimeUnit unit) throws InterruptedException;

    boolean offerLast(E e, long timeout, TimeUnit unit) throws InterruptedException;

    E takeFirst() throws InterruptedException;

    E takeLast() throws InterruptedException;

    E pollFirst(long timeout, TimeUnit unit) throws InterruptedException;

    E pollLast(long timeout, TimeUnit unit) throws InterruptedException;
}
