// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A reference and a mark read and set together. Every operation holds the object's
// monitor, as AtomicReference's do; the references compare by identity, and a weak
// compare-and-set fails only where the strong one does.
package java.util.concurrent.atomic;

public class AtomicMarkableReference<V> {

    private V reference;

    private boolean mark;

    public AtomicMarkableReference(V initialRef, boolean initialMark) {
        this.reference = initialRef;
        this.mark = initialMark;
    }

    public synchronized V getReference() {
        return this.reference;
    }

    public synchronized boolean isMarked() {
        return this.mark;
    }

    public synchronized V get(boolean[] markHolder) {
        markHolder[0] = this.mark;
        return this.reference;
    }

    public boolean weakCompareAndSet(V expectedReference, V newReference, boolean expectedMark, boolean newMark) {
        return compareAndSet(expectedReference, newReference, expectedMark, newMark);
    }

    public synchronized boolean compareAndSet(V expectedReference, V newReference, boolean expectedMark,
            boolean newMark) {
        if (this.reference != expectedReference || this.mark != expectedMark) {
            return false;
        }
        this.reference = newReference;
        this.mark = newMark;
        return true;
    }

    public synchronized void set(V newReference, boolean newMark) {
        this.reference = newReference;
        this.mark = newMark;
    }

    public synchronized boolean attemptMark(V expectedReference, boolean newMark) {
        if (this.reference != expectedReference) {
            return false;
        }
        this.mark = newMark;
        return true;
    }
}
