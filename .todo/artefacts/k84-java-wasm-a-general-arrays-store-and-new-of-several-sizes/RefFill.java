public class RefFill {
    public static void main(String[] args) {
        String[] a = new String[1000];
        String x = "x", y = "y";
        long s = 0;
        for (int r = 0; r < 100000; r++) {
            String v = (r & 1) == 0 ? x : y;
            for (int i = 0; i < 1000; i++) a[i] = v;
            s += a[r % 1000].length();
        }
        System.out.println(s);
    }
}
