public class HandOff {
    static String message;
    static final Object lock = new Object();
    public static void main(String[] args) throws InterruptedException {
        Thread receiver = new Thread(() -> {
            synchronized (lock) {
                try {
                    while (message == null) {
                        lock.wait();
                    }
                }
                catch (InterruptedException e) {
                    return;
                }
                System.out.println("got " + message);
            }
        });
        receiver.start();
        synchronized (lock) {
            message = "hello";
            lock.notify();
        }
        receiver.join();
        System.out.println("end");
    }
}
