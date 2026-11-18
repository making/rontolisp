public class StopFlag {
    static volatile boolean running = true;
    public static void main(String[] args) throws InterruptedException {
        Thread spinner = new Thread(() -> {
            long turns = 0;
            while (running) {
                turns++;
            }
            System.out.println("stopped " + (turns > 0));
        });
        spinner.start();
        Thread.sleep(20);
        running = false;
        spinner.join();
        System.out.println("end");
    }
}
