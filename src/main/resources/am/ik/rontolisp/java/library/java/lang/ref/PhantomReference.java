// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.ref;

// A phantom reference: get() answers null always; refersTo tells the object it was made
// with until the program clears it.
public non-sealed class PhantomReference<T> extends Reference<T> {

    public PhantomReference(T referent, ReferenceQueue<? super T> q) {
        super(referent, q);
    }

    public T get() {
        return null;
    }
}
