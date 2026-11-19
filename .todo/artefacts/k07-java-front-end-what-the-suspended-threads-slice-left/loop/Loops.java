public class Loops {
    static volatile int stop;
    static int find(int[] a, int v) {
        for (int i = 0; i < a.length; i++) {
            if (stop > 0) break;
            if (a[i] == v) return i;
            if (a[i] < 0) continue;
            stop = 0;
        }
        return -1;
    }
    static int outer() {
        int n = 0;
        scan:
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                if (stop > 5) break scan;
                if (stop > 3) continue scan;
                n += stop;
            }
        }
        do { n++; } while (stop > n);
        while (stop > n) { n++; }
        return n;
    }
    public static void main(String[] args) throws Exception {
        Thread t = new Thread(() -> System.out.println(find(new int[] {1, 2, 3}, 2) + " " + outer()));
        t.start();
        t.join();
    }
}
