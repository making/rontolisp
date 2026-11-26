// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.ref;

// A weak reference: what Reference says, the object kept until the program clears it.
public non-sealed class WeakReference<T> extends Reference<T> {

    public WeakReference(T referent) {
        super(referent, null);
    }

    public WeakReference(T referent, ReferenceQueue<? super T> q) {
        super(referent, q);
    }
}
