// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.Predicate;
import rontolisp.internal.Splits;

// The view Deque.reversed() answers: each member calls the deque's public member of the
// other end, so what the deque refuses (a null, a full bound) the view refuses.
final class ReversedDeque<E> extends AbstractCollection<E> implements Deque<E> {

    final Deque<E> deque;

    ReversedDeque(Deque<E> deque) {
        this.deque = deque;
    }

    public Deque<E> reversed() {
        return deque;
    }

    public int size() {
        return deque.size();
    }

    public boolean isEmpty() {
        return deque.isEmpty();
    }

    public boolean contains(Object o) {
        return deque.contains(o);
    }

    public Iterator<E> iterator() {
        return deque.descendingIterator();
    }

    public Iterator<E> descendingIterator() {
        return deque.iterator();
    }

    public Spliterator<E> spliterator() {
        return Splits.of(this, Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED);
    }

    public boolean add(E e) {
        deque.addFirst(e);
        return true;
    }

    public void addFirst(E e) {
        deque.addLast(e);
    }

    public void addLast(E e) {
        deque.addFirst(e);
    }

    public boolean offer(E e) {
        return deque.offerFirst(e);
    }

    public boolean offerFirst(E e) {
        return deque.offerLast(e);
    }

    public boolean offerLast(E e) {
        return deque.offerFirst(e);
    }

    public void push(E e) {
        deque.addLast(e);
    }

    public E pop() {
        return deque.removeLast();
    }

    public E remove() {
        return deque.removeLast();
    }

    public E poll() {
        return deque.pollLast();
    }

    public E element() {
        return deque.getLast();
    }

    public E peek() {
        return deque.peekLast();
    }

    public E removeFirst() {
        return deque.removeLast();
    }

    public E removeLast() {
        return deque.removeFirst();
    }

    public E pollFirst() {
        return deque.pollLast();
    }

    public E pollLast() {
        return deque.pollFirst();
    }

    public E getFirst() {
        return deque.getLast();
    }

    public E getLast() {
        return deque.getFirst();
    }

    public E peekFirst() {
        return deque.peekLast();
    }

    public E peekLast() {
        return deque.peekFirst();
    }

    public boolean remove(Object o) {
        return deque.removeLastOccurrence(o);
    }

    public boolean removeFirstOccurrence(Object o) {
        return deque.removeLastOccurrence(o);
    }

    public boolean removeLastOccurrence(Object o) {
        return deque.removeFirstOccurrence(o);
    }

    public boolean removeIf(Predicate<? super E> filter) {
        return deque.removeIf(filter);
    }

    public boolean removeAll(Collection<?> c) {
        return deque.removeAll(c);
    }

    public boolean retainAll(Collection<?> c) {
        return deque.retainAll(c);
    }

    public void clear() {
        deque.clear();
    }
}
