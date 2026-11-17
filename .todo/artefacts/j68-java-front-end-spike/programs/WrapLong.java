public class WrapLong {
    public static void main(String[] args) {
        long g = 1;
        for (int i = 0; i < 10000000; i++) {
            g = g * 6364136223846793005L + 1442695040888963407L;
        }
        System.out.println(g);
    }
}
