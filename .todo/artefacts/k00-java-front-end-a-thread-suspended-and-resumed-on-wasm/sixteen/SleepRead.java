public class SleepRead {
    static volatile int value;
    public static void main(String[] args) throws InterruptedException {
        Thread t = new Thread(() -> {
            value = 99;
        });
        t.start();
        Thread.sleep(50);
        System.out.println(value);
    }
}
