// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.ref;

// A soft reference: what Reference says, the object kept until the program clears it.
public non-sealed class SoftReference<T> extends Reference<T> {

    public SoftReference(T referent) {
        super(referent, null);
    }

    public SoftReference(T referent, ReferenceQueue<? super T> q) {
        super(referent, q);
    }
}
