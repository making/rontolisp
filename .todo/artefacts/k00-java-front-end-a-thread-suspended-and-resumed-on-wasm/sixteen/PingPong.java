public class PingPong {
    static int turn;
    static final Object table = new Object();

    static Thread player(String word, int mine) {
        return new Thread(() -> {
            try {
                for (int i = 0; i < 3; i++) {
                    synchronized (table) {
                        while (turn != mine) {
                            table.wait();
                        }
                        System.out.println(word);
                        turn = 1 - mine;
                        table.notifyAll();
                    }
                }
            }
            catch (InterruptedException e) {
                System.out.println("interrupted");
            }
        });
    }

    public static void main(String[] args) throws InterruptedException {
        Thread ping = player("ping", 0);
        Thread pong = player("pong", 1);
        pong.start();
        ping.start();
        ping.join();
        pong.join();
        System.out.println("end");
    }
}
