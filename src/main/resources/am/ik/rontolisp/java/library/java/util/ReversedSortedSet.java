// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import rontolisp.internal.Splits;

// The view SortedSet.reversed() answers for a sorted set that is not navigable: the set
// by the reverse of its order, walked from its last element back, each next element the
// last of the set's head before the one given, so a walk sees what was added ahead of it.
// A range of the view is a first element (from, kept) and one it stops before (to), in
// the view's order, either null where there is none. What a JDK 25's view answers is kept
// where it is its own: a null bound is none, a range of the whole view may end before it
// starts and is empty, a range of a range refuses a bound outside it, and tailSet of a
// range keeps only the end it stops before.
final class ReversedSortedSet<E> extends AbstractSet<E> implements SortedSet<E> {

    final SortedSet<E> set;

    // the view's order, the reverse of the set's
    private final Comparator<? super E> order;

    private final E from;

    private final E to;

    ReversedSortedSet(SortedSet<E> set) {
        this(set, Collections.reverseOrder(set.comparator()), null, null);
    }

    private ReversedSortedSet(SortedSet<E> set, Comparator<? super E> order, E from, E to) {
        this.set = set;
        this.order = order;
        this.from = from;
        this.to = to;
    }

    private boolean bounded() {
        return from != null || to != null;
    }

    private boolean inRange(E e) {
        return (from == null || order.compare(from, e) <= 0) && (to == null || order.compare(e, to) < 0);
    }

    private void bound(E e) {
        if (!inRange(e)) {
            throw new IllegalArgumentException();
        }
    }

    public Comparator<? super E> comparator() {
        return order;
    }

    public SortedSet<E> reversed() {
        return bounded() ? new ReversedSortedSet<>(this) : set;
    }

    public int size() {
        if (!bounded()) {
            return set.size();
        }
        int count = 0;
        for (Iterator<E> each = iterator(); each.hasNext(); each.next()) {
            count++;
        }
        return count;
    }

    public boolean isEmpty() {
        return bounded() ? !iterator().hasNext() : set.isEmpty();
    }

    public boolean contains(Object o) {
        return inRange((E) o) && set.contains(o);
    }

    public boolean add(E e) {
        bound(e);
        return set.add(e);
    }

    public boolean remove(Object o) {
        return inRange((E) o) && set.remove(o);
    }

    public Iterator<E> iterator() {
        return new Walk<>(this);
    }

    public Spliterator<E> spliterator() {
        return Splits.of(this, Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED);
    }

    public E first() {
        return iterator().next();
    }

    public E last() {
        if (!bounded()) {
            return set.first();
        }
        Iterator<E> each = iterator();
        E last = each.next();
        while (each.hasNext()) {
            last = each.next();
        }
        return last;
    }

    public SortedSet<E> headSet(E toElement) {
        if (bounded()) {
            bound(toElement);
        }
        return new ReversedSortedSet<>(set, order, from, toElement);
    }

    public SortedSet<E> tailSet(E fromElement) {
        if (!bounded()) {
            return new ReversedSortedSet<>(set, order, fromElement, null);
        }
        bound(fromElement);
        return new ReversedSortedSet<>(set, order, null, to);
    }

    public SortedSet<E> subSet(E fromElement, E toElement) {
        if (bounded()) {
            bound(fromElement);
            bound(toElement);
            if (order.compare(fromElement, toElement) > 0) {
                throw new IllegalArgumentException();
            }
        }
        return new ReversedSortedSet<>(set, order, fromElement, toElement);
    }

    // The elements of the view, each found when it is asked for.
    static final class Walk<E> implements Iterator<E> {

        private final ReversedSortedSet<E> view;

        // an element was given, and which
        private boolean begun;

        private E given;

        // the element ahead, found by hasNext and kept until next() gives it
        private boolean found;

        private E ahead;

        private boolean removable;

        Walk(ReversedSortedSet<E> view) {
            this.view = view;
        }

        private void find() {
            SortedSet<E> set = view.set;
            SortedSet<E> rest;
            if (begun) {
                rest = set.headSet(given);
            }
            else if (view.from != null) {
                SortedSet<E> above = set.tailSet(view.from);
                if (!above.isEmpty()) {
                    E least = above.first();
                    if (view.order.compare(least, view.from) == 0) {
                        ahead = least;
                        found = view.to == null || view.order.compare(least, view.to) < 0;
                        return;
                    }
                }
                rest = set.headSet(view.from);
            }
            else {
                rest = set;
            }
            if (rest.isEmpty()) {
                return;
            }
            ahead = rest.last();
            found = view.to == null || view.order.compare(ahead, view.to) < 0;
        }

        public boolean hasNext() {
            if (!found) {
                find();
            }
            return found;
        }

        public E next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            found = false;
            begun = true;
            given = ahead;
            removable = true;
            return given;
        }

        public void remove() {
            if (!removable) {
                throw new IllegalStateException();
            }
            removable = false;
            view.set.remove(given);
        }
    }
}
