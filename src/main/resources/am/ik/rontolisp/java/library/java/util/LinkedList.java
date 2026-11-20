// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import rontolisp.internal.Splits;

// Cells linked both ways: a list, and a deque whose elements may be null. An index is
// walked to from the nearer end.
public class LinkedList<E> extends AbstractSequentialList<E> implements List<E>, Deque<E> {

    static final class Cell<E> {

        E item;

        Cell<E> before;

        Cell<E> after;

        Cell(Cell<E> before, E item, Cell<E> after) {
            this.before = before;
            this.item = item;
            this.after = after;
        }
    }

    private Cell<E> first;

    private Cell<E> last;

    private int count;

    private int changes;

    public LinkedList() {
    }

    public LinkedList(Collection<? extends E> c) {
        addAll(c);
    }

    private void linkFirst(E e) {
        Cell<E> cell = new Cell<>(null, e, first);
        if (first == null) {
            last = cell;
        }
        else {
            first.before = cell;
        }
        first = cell;
        count++;
        changes++;
    }

    private void linkLast(E e) {
        Cell<E> cell = new Cell<>(last, e, null);
        if (last == null) {
            first = cell;
        }
        else {
            last.after = cell;
        }
        last = cell;
        count++;
        changes++;
    }

    private void linkBefore(E e, Cell<E> place) {
        Cell<E> before = place.before;
        Cell<E> cell = new Cell<>(before, e, place);
        place.before = cell;
        if (before == null) {
            first = cell;
        }
        else {
            before.after = cell;
        }
        count++;
        changes++;
    }

    private E unlink(Cell<E> cell) {
        Cell<E> before = cell.before;
        Cell<E> after = cell.after;
        if (before == null) {
            first = after;
        }
        else {
            before.after = after;
        }
        if (after == null) {
            last = before;
        }
        else {
            after.before = before;
        }
        count--;
        changes++;
        return cell.item;
    }

    private Cell<E> cell(int index) {
        if (index < count >> 1) {
            Cell<E> cell = first;
            for (int i = 0; i < index; i++) {
                cell = cell.after;
            }
            return cell;
        }
        Cell<E> cell = last;
        for (int i = count - 1; i > index; i--) {
            cell = cell.before;
        }
        return cell;
    }

    private void element(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + count);
        }
    }

    private void position(int index) {
        if (index < 0 || index > count) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + count);
        }
    }

    public int size() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public boolean add(E e) {
        linkLast(e);
        return true;
    }

    public void add(int index, E element) {
        position(index);
        if (index == count) {
            linkLast(element);
        }
        else {
            linkBefore(element, cell(index));
        }
    }

    public boolean addAll(Collection<? extends E> c) {
        return addAll(count, c);
    }

    public boolean addAll(int index, Collection<? extends E> c) {
        position(index);
        Object[] added = c.toArray();
        if (added.length == 0) {
            return false;
        }
        Cell<E> place = index == count ? null : cell(index);
        for (Object e : added) {
            if (place == null) {
                linkLast((E) e);
            }
            else {
                linkBefore((E) e, place);
            }
        }
        return true;
    }

    public E get(int index) {
        element(index);
        return cell(index).item;
    }

    public E set(int index, E element) {
        element(index);
        Cell<E> cell = cell(index);
        E old = cell.item;
        cell.item = element;
        return old;
    }

    public E remove(int index) {
        element(index);
        return unlink(cell(index));
    }

    public boolean remove(Object o) {
        for (Cell<E> cell = first; cell != null; cell = cell.after) {
            if (o == null ? cell.item == null : o.equals(cell.item)) {
                unlink(cell);
                return true;
            }
        }
        return false;
    }

    public boolean removeFirstOccurrence(Object o) {
        return remove(o);
    }

    public boolean removeLastOccurrence(Object o) {
        for (Cell<E> cell = last; cell != null; cell = cell.before) {
            if (o == null ? cell.item == null : o.equals(cell.item)) {
                unlink(cell);
                return true;
            }
        }
        return false;
    }

    public int indexOf(Object o) {
        int index = 0;
        for (Cell<E> cell = first; cell != null; cell = cell.after) {
            if (o == null ? cell.item == null : o.equals(cell.item)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    public int lastIndexOf(Object o) {
        int index = count - 1;
        for (Cell<E> cell = last; cell != null; cell = cell.before) {
            if (o == null ? cell.item == null : o.equals(cell.item)) {
                return index;
            }
            index--;
        }
        return -1;
    }

    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    public void clear() {
        first = null;
        last = null;
        count = 0;
        changes++;
    }

    public void addFirst(E e) {
        linkFirst(e);
    }

    public void addLast(E e) {
        linkLast(e);
    }

    public boolean offerFirst(E e) {
        linkFirst(e);
        return true;
    }

    public boolean offerLast(E e) {
        linkLast(e);
        return true;
    }

    public boolean offer(E e) {
        linkLast(e);
        return true;
    }

    public E getFirst() {
        if (first == null) {
            throw new NoSuchElementException();
        }
        return first.item;
    }

    public E getLast() {
        if (last == null) {
            throw new NoSuchElementException();
        }
        return last.item;
    }

    public E element() {
        return getFirst();
    }

    public E peekFirst() {
        return first == null ? null : first.item;
    }

    public E peekLast() {
        return last == null ? null : last.item;
    }

    public E peek() {
        return peekFirst();
    }

    public E removeFirst() {
        if (first == null) {
            throw new NoSuchElementException();
        }
        return unlink(first);
    }

    public E removeLast() {
        if (last == null) {
            throw new NoSuchElementException();
        }
        return unlink(last);
    }

    public E remove() {
        return removeFirst();
    }

    public E pollFirst() {
        return first == null ? null : unlink(first);
    }

    public E pollLast() {
        return last == null ? null : unlink(last);
    }

    public E poll() {
        return pollFirst();
    }

    public void push(E e) {
        linkFirst(e);
    }

    public E pop() {
        return removeFirst();
    }

    public ListIterator<E> listIterator(int index) {
        position(index);
        return new Walk<>(this, index);
    }

    public Iterator<E> descendingIterator() {
        return new Backward<>(new Walk<>(this, count));
    }

    public Spliterator<E> spliterator() {
        return Splits.counted(this, Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED);
    }

    public LinkedList<E> reversed() {
        return new Reversed<>(this);
    }

    // The view reversed() answers, a LinkedList that holds no cell: a member that has an
    // index is the list's reversed view's, one that has an end is the list's member of
    // the other end.
    static final class Reversed<E> extends LinkedList<E> {

        private final LinkedList<E> list;

        private final ReversedList<E> view;

        Reversed(LinkedList<E> list) {
            this.list = list;
            this.view = new ReversedList<>(list, true);
        }

        public LinkedList<E> reversed() {
            return list;
        }

        public int size() {
            return list.size();
        }

        public boolean isEmpty() {
            return list.isEmpty();
        }

        public boolean contains(Object o) {
            return list.contains(o);
        }

        public boolean add(E e) {
            list.addFirst(e);
            return true;
        }

        public void add(int index, E element) {
            view.add(index, element);
        }

        public boolean addAll(Collection<? extends E> c) {
            return view.addAll(c);
        }

        public boolean addAll(int index, Collection<? extends E> c) {
            return view.addAll(index, c);
        }

        public E get(int index) {
            return view.get(index);
        }

        public E set(int index, E element) {
            return view.set(index, element);
        }

        public E remove(int index) {
            return view.remove(index);
        }

        public boolean remove(Object o) {
            return list.removeLastOccurrence(o);
        }

        public boolean removeFirstOccurrence(Object o) {
            return list.removeLastOccurrence(o);
        }

        public boolean removeLastOccurrence(Object o) {
            return list.removeFirstOccurrence(o);
        }

        public int indexOf(Object o) {
            return view.indexOf(o);
        }

        public int lastIndexOf(Object o) {
            return view.lastIndexOf(o);
        }

        public void clear() {
            list.clear();
        }

        public void addFirst(E e) {
            list.addLast(e);
        }

        public void addLast(E e) {
            list.addFirst(e);
        }

        public boolean offerFirst(E e) {
            return list.offerLast(e);
        }

        public boolean offerLast(E e) {
            return list.offerFirst(e);
        }

        public boolean offer(E e) {
            return list.offerFirst(e);
        }

        public E getFirst() {
            return list.getLast();
        }

        public E getLast() {
            return list.getFirst();
        }

        public E element() {
            return list.getLast();
        }

        public E peekFirst() {
            return list.peekLast();
        }

        public E peekLast() {
            return list.peekFirst();
        }

        public E peek() {
            return list.peekLast();
        }

        public E removeFirst() {
            return list.removeLast();
        }

        public E removeLast() {
            return list.removeFirst();
        }

        public E remove() {
            return list.removeLast();
        }

        public E pollFirst() {
            return list.pollLast();
        }

        public E pollLast() {
            return list.pollFirst();
        }

        public E poll() {
            return list.pollLast();
        }

        public void push(E e) {
            list.addLast(e);
        }

        public E pop() {
            return list.removeLast();
        }

        public ListIterator<E> listIterator(int index) {
            return view.listIterator(index);
        }

        public Iterator<E> descendingIterator() {
            return list.iterator();
        }

        public Spliterator<E> spliterator() {
            return Splits.of(this, Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED);
        }

        public boolean removeIf(Predicate<? super E> filter) {
            return list.removeIf(filter);
        }

        public void replaceAll(UnaryOperator<E> operator) {
            list.replaceAll(operator);
        }

        public void sort(Comparator<? super E> c) {
            view.sort(c);
        }
    }

    static final class Walk<E> implements ListIterator<E> {

        private final LinkedList<E> list;

        // the cell next() answers, null past the last
        private Cell<E> ahead;

        // the cell next() or previous() answered last, null once removed or one added
        private Cell<E> last;

        private int index;

        private int expected;

        Walk(LinkedList<E> list, int index) {
            this.list = list;
            this.ahead = index == list.count ? null : list.cell(index);
            this.index = index;
            this.expected = list.changes;
        }

        private void unchanged() {
            if (list.changes != expected) {
                throw new ConcurrentModificationException();
            }
        }

        public boolean hasNext() {
            return index < list.count;
        }

        public E next() {
            unchanged();
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            last = ahead;
            ahead = ahead.after;
            index++;
            return last.item;
        }

        public boolean hasPrevious() {
            return index > 0;
        }

        public E previous() {
            unchanged();
            if (!hasPrevious()) {
                throw new NoSuchElementException();
            }
            ahead = ahead == null ? list.last : ahead.before;
            last = ahead;
            index--;
            return last.item;
        }

        public int nextIndex() {
            return index;
        }

        public int previousIndex() {
            return index - 1;
        }

        public void remove() {
            unchanged();
            if (last == null) {
                throw new IllegalStateException();
            }
            Cell<E> after = last.after;
            list.unlink(last);
            if (ahead == last) {
                ahead = after;
            }
            else {
                index--;
            }
            last = null;
            expected = list.changes;
        }

        public void set(E e) {
            if (last == null) {
                throw new IllegalStateException();
            }
            unchanged();
            last.item = e;
        }

        public void add(E e) {
            unchanged();
            last = null;
            if (ahead == null) {
                list.linkLast(e);
            }
            else {
                list.linkBefore(e, ahead);
            }
            index++;
            expected = list.changes;
        }
    }

    static final class Backward<E> implements Iterator<E> {

        private final Walk<E> walk;

        Backward(Walk<E> walk) {
            this.walk = walk;
        }

        public boolean hasNext() {
            return walk.hasPrevious();
        }

        public E next() {
            return walk.previous();
        }

        public void remove() {
            walk.remove();
        }
    }
}
