public class StopInterrupt {
    public static void main(String[] args) throws InterruptedException {
        Thread spinner = new Thread(() -> {
            long turns = 0;
            while (!Thread.currentThread().isInterrupted()) {
                turns++;
            }
            System.out.println("stopped " + (turns > 0));
        });
        spinner.start();
        Thread.sleep(20);
        spinner.interrupt();
        spinner.join();
        System.out.println("end");
    }
}
