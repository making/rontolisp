public class Wrap {
    public static void main(String[] args) {
        int h = 0;
        for (int i = 0; i < 30000000; i++) {
            h = 31 * h + i % 7;
        }
        System.out.println(h);
        long g = 1;
        for (int i = 0; i < 10000000; i++) {
            g = g * 6364136223846793005L + 1442695040888963407L;
        }
        System.out.println(g);
    }
}
