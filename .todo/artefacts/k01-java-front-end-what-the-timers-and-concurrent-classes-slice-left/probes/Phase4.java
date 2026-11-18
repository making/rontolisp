import java.util.concurrent.*;

public class Phase4 {
    public static void main(String[] args) throws Exception {
        Phaser p = new Phaser(1) {
            protected boolean onAdvance(int phase, int registeredParties) {
                return phase >= 1;
            }
        };
        System.out.println(p.arriveAndAwaitAdvance() + " " + p.isTerminated() + " " + p.arriveAndAwaitAdvance() + " " + p.isTerminated() + " " + p.getPhase() + " " + p.arriveAndAwaitAdvance() + " " + p.arrive());
        Phaser q = new Phaser(1);
        System.out.println(q.arriveAndAwaitAdvance() + " " + q.arriveAndDeregister() + " " + q.isTerminated() + " " + q.getPhase());
        // through a tier: the last arrival of the tree is a tier's
        Phaser root = new Phaser() {
            protected boolean onAdvance(int phase, int registeredParties) {
                return true;
            }
        };
        Phaser tier = new Phaser(root, 1);
        System.out.println(tier.arriveAndAwaitAdvance() + " " + tier.isTerminated() + " " + tier.getPhase());
        // wraps at the largest phase? not probed: 2^31 arrivals
    }
}
