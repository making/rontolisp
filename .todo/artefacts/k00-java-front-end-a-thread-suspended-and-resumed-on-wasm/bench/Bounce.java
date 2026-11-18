public class Bounce {
    static int turn;
    static final Object table = new Object();
    public static void main(String[] args) throws InterruptedException {
        Thread other = new Thread(() -> {
            try {
                for (int i = 0; i < 200000; i++) {
                    synchronized (table) {
                        while (turn != 1) {
                            table.wait();
                        }
                        turn = 0;
                        table.notifyAll();
                    }
                }
            }
            catch (InterruptedException e) {
                return;
            }
        });
        other.start();
        for (int i = 0; i < 200000; i++) {
            synchronized (table) {
                while (turn != 0) {
                    table.wait();
                }
                turn = 1;
                table.notifyAll();
            }
        }
        other.join();
        System.out.println("done");
    }
}
