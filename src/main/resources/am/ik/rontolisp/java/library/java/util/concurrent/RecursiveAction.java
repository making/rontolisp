// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

public abstract class RecursiveAction extends ForkJoinTask<Void> {

    public RecursiveAction() {
    }

    protected abstract void compute();

    public final Void getRawResult() {
        return null;
    }

    protected final void setRawResult(Void value) {
    }

    protected final boolean exec() {
        compute();
        return true;
    }
}
