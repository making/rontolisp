public class Uncaught {
    public static void main(String[] args) throws InterruptedException {
        Thread t = new Thread(() -> {
            System.out.println("before");
            throw new IllegalStateException("boom");
        }, "thrower");
        t.start();
        t.join();
        System.out.println("main goes on " + t.isAlive());
    }
}
