public class LongSum {
    static long sum(long[] a) {
        long s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * 31 + (a[i] >>> 7);
        }
        return s;
    }

    public static void main(String[] args) {
        long[] a = new long[1000];
        for (int i = 0; i < a.length; i++) {
            a[i] = (long) i * 2654435761L;
        }
        long t = 0;
        for (int r = 0; r < 100000; r++) {
            t += sum(a);
        }
        System.out.println(t);
    }
}
