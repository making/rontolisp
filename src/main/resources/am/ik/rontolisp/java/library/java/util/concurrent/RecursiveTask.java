// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

public abstract class RecursiveTask<V> extends ForkJoinTask<V> {

    private V result;

    public RecursiveTask() {
    }

    protected abstract V compute();

    public final V getRawResult() {
        return this.result;
    }

    protected final void setRawResult(V value) {
        this.result = value;
    }

    protected final boolean exec() {
        this.result = compute();
        return true;
    }
}
