public class Init3 {
    static class Later {
        static int n;
        static { if (n == 0) throw new IllegalArgumentException("later"); }
    }
    static class Slow {
        static int n;
        static {
            try { Thread.sleep(50); } catch (InterruptedException e) { }
            if (n == 0) throw new IllegalStateException("slow");
        }
    }
    static void show(String what, Throwable t) {
        System.out.println(what + ": " + t + " | cause " + t.getCause());
    }
    public static void main(String[] args) throws InterruptedException {
        Thread other = new Thread(() -> {
            try { System.out.println(Later.n); } catch (Throwable t) { show("thread", t); }
        }, "worker");
        other.start();
        other.join();
        try { System.out.println(Later.n); } catch (Throwable t) { show("later", t); }
        Thread first = new Thread(() -> {
            try { System.out.println(Slow.n); } catch (Throwable t) { show("first", t); }
        }, "first");
        first.start();
        first.join();
        try { System.out.println(Slow.n); } catch (Throwable t) { show("second", t); }
        System.out.println(Later.n);
    }
}
