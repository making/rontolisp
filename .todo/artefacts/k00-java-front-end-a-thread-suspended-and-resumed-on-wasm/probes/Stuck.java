public class Stuck {
    static final Object a = new Object();
    static final Object b = new Object();
    public static void main(String[] args) throws InterruptedException {
        Thread one = new Thread(() -> {
            synchronized (a) {
                try {
                    Thread.sleep(10);
                }
                catch (InterruptedException e) {
                    return;
                }
                synchronized (b) {
                    System.out.println("one has both");
                }
            }
        });
        Thread two = new Thread(() -> {
            synchronized (b) {
                try {
                    Thread.sleep(10);
                }
                catch (InterruptedException e) {
                    return;
                }
                synchronized (a) {
                    System.out.println("two has both");
                }
            }
        });
        one.start();
        two.start();
        System.out.println("started");
        one.join();
        two.join();
        System.out.println("not reached");
    }
}
