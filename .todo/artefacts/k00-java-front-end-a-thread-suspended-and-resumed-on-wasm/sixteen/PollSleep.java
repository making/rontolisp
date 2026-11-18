public class PollSleep {
    static volatile boolean ready;
    static int value;
    public static void main(String[] args) throws InterruptedException {
        Thread t = new Thread(() -> {
            value = 42;
            ready = true;
        });
        t.start();
        while (!ready) {
            Thread.sleep(1);
        }
        System.out.println(value);
    }
}
