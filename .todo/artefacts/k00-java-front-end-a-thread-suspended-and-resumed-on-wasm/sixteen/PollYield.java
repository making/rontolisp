public class PollYield {
    static volatile boolean ready;
    static int value;
    public static void main(String[] args) {
        Thread t = new Thread(() -> {
            value = 7;
            ready = true;
        });
        t.start();
        while (!ready) {
            Thread.yield();
        }
        System.out.println(value);
    }
}
