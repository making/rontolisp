public class DivLoop {
    static int step(int acc, int i, int d) { return acc + i / d + i % (d + 1); }
    public static void main(String[] args) {
        int acc = 0;
        for (int i = 0; i < 200000000; i++) acc = step(acc, i, (i & 7) + 1);
        System.out.println(acc);
    }
}
