public class Cause {
    public static void main(String[] args) {
        Thread t = new Thread(() -> { throw new IllegalStateException("in thread", new Error("root")); }, "worker");
        t.start();
        try { t.join(); } catch (InterruptedException e) { }
        RuntimeException top = new RuntimeException("top", new IllegalArgumentException("middle", new Error("root")));
        top.addSuppressed(new IllegalStateException("aside"));
        throw top;
    }
}
