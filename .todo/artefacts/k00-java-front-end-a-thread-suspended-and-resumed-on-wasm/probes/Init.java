public class Init {
    static class Slow {
        static final int value;
        static {
            System.out.println("initializing");
            try {
                Thread.sleep(20);
            }
            catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            value = 42;
            System.out.println("initialized");
        }
        static int read() {
            return value;
        }
    }
    public static void main(String[] args) throws InterruptedException {
        Thread first = new Thread(() -> System.out.println("first " + Slow.read()));
        Thread second = new Thread(() -> System.out.println("second " + Slow.read()));
        first.start();
        Thread.sleep(5);
        second.start();
        first.join();
        second.join();
        System.out.println("main " + Slow.read());
    }
}
