public class Exits {
    public static void main(String[] args) throws InterruptedException {
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(10);
            }
            catch (InterruptedException e) {
                return;
            }
            System.out.println("worker exits");
            System.exit(3);
        });
        worker.start();
        Thread.sleep(1000);
        System.out.println("not reached");
    }
}
