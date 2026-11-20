public class LongFill {
    public static void main(String[] args) {
        long[] a = new long[1000];
        int[] b = new int[1000];
        long s = 0;
        for (int r = 0; r < 100000; r++) {
            for (int i = 0; i < 1000; i++) a[i] = (long) i * r;
            s += a[r % 1000];
        }
        System.out.println(s);
    }
}
