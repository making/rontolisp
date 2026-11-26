import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
public class UpdBase {
    volatile String s = "a";
    static final AtomicReferenceFieldUpdater<UpdBase, String> U = AtomicReferenceFieldUpdater.newUpdater(UpdBase.class, String.class, "s");
    public static void main(String[] args) {
        UpdBase b = new UpdBase();
        System.out.println(U.compareAndSet(b, "a", "b") + " " + b.s);
    }
}
