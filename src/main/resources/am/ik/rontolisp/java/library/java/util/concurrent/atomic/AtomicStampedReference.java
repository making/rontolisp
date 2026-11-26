// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A reference and a stamp read and set together. Every operation holds the object's
// monitor, as AtomicReference's do; the references compare by identity, and a weak
// compare-and-set fails only where the strong one does.
package java.util.concurrent.atomic;

public class AtomicStampedReference<V> {

    private V reference;

    private int stamp;

    public AtomicStampedReference(V initialRef, int initialStamp) {
        this.reference = initialRef;
        this.stamp = initialStamp;
    }

    public synchronized V getReference() {
        return this.reference;
    }

    public synchronized int getStamp() {
        return this.stamp;
    }

    public synchronized V get(int[] stampHolder) {
        stampHolder[0] = this.stamp;
        return this.reference;
    }

    public boolean weakCompareAndSet(V expectedReference, V newReference, int expectedStamp, int newStamp) {
        return compareAndSet(expectedReference, newReference, expectedStamp, newStamp);
    }

    public synchronized boolean compareAndSet(V expectedReference, V newReference, int expectedStamp,
            int newStamp) {
        if (this.reference != expectedReference || this.stamp != expectedStamp) {
            return false;
        }
        this.reference = newReference;
        this.stamp = newStamp;
        return true;
    }

    public synchronized void set(V newReference, int newStamp) {
        this.reference = newReference;
        this.stamp = newStamp;
    }

    public synchronized boolean attemptStamp(V expectedReference, int newStamp) {
        if (this.reference != expectedReference) {
            return false;
        }
        this.stamp = newStamp;
        return true;
    }
}
