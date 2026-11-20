import java.util.*;
import java.util.function.*;

public class Rand {
    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        byte[] b = new byte[11];
        Random r = new Random(3);
        r.nextBytes(b);
        System.out.println(Arrays.toString(b) + " " + r.nextInt());
        byte[] none = new byte[0];
        r.nextBytes(none);
        System.out.println(r.nextInt() + " " + r.nextFloat() + " " + r.nextFloat() + " " + new Random(9).nextFloat());
        long agree = 0;
        long total = 0;
        StringBuilder firsts = new StringBuilder();
        int[][] ranges = { { 0, 10 }, { -8, 8 }, { 5, 6 }, { -1000000, 1000000 }, { Integer.MIN_VALUE, Integer.MAX_VALUE }, { 0, 1 << 30 }, { -3, 1 << 30 }, { 1, 100 }, { Integer.MIN_VALUE, 0 }, { -2, Integer.MAX_VALUE } };
        for (int seed = 0; seed < 50; seed++) {
            for (int[] range : ranges) {
                Random a = new Random(seed);
                Random c = new Random(seed);
                int[] s = c.ints(5, range[0], range[1]).toArray();
                for (int i = 0; i < 5; i++) {
                    int x = a.nextInt(range[0], range[1]);
                    total++;
                    if (x == s[i]) {
                        agree++;
                    }
                    if (seed < 2 && i == 0) {
                        firsts.append(x).append(' ');
                    }
                }
            }
        }
        System.out.println("ints agree " + agree + "/" + total + " " + firsts);
        agree = 0;
        total = 0;
        firsts.setLength(0);
        long[][] lranges = { { 0, 10 }, { -8, 8 }, { 0, 1L << 40 }, { -5, (1L << 62) + 3 }, { Long.MIN_VALUE, Long.MAX_VALUE }, { 7, 1000000007 } };
        for (int seed = 0; seed < 50; seed++) {
            for (long[] range : lranges) {
                Random a = new Random(seed);
                Random c = new Random(seed);
                long[] s = c.longs(5, range[0], range[1]).toArray();
                for (int i = 0; i < 5; i++) {
                    long x = a.nextLong(range[0], range[1]);
                    total++;
                    if (x == s[i]) {
                        agree++;
                    }
                    if (seed < 2 && i == 0) {
                        firsts.append(x).append(' ');
                    }
                }
            }
        }
        System.out.println("longs agree " + agree + "/" + total + " " + firsts);
        agree = 0;
        total = 0;
        for (int seed = 0; seed < 50; seed++) {
            for (long bound : new long[] { 1, 10, 1L << 40, (1L << 62) + 3, Long.MAX_VALUE }) {
                Random a = new Random(seed);
                Random c = new Random(seed);
                long[] s = c.longs(5, 0, bound).toArray();
                for (int i = 0; i < 5; i++) {
                    total++;
                    if (a.nextLong(bound) == s[i]) {
                        agree++;
                    }
                }
            }
        }
        System.out.println("long bound agree " + agree + "/" + total);
        agree = 0;
        total = 0;
        for (int seed = 0; seed < 50; seed++) {
            for (double[] range : new double[][] { { 0, 1 }, { -2.5, 7.25 }, { 1e-300, 1e300 }, { 0, 1e-10 } }) {
                Random a = new Random(seed);
                Random c = new Random(seed);
                double[] s = c.doubles(5, range[0], range[1]).toArray();
                for (int i = 0; i < 5; i++) {
                    total++;
                    if (a.nextDouble(range[0], range[1]) == s[i]) {
                        agree++;
                    }
                }
                Random d = new Random(seed);
                Random e = new Random(seed);
                double[] t = e.doubles(5, 0, range[1]).toArray();
                for (int i = 0; i < 5; i++) {
                    total++;
                    if (d.nextDouble(range[1]) == t[i]) {
                        agree++;
                    }
                }
            }
        }
        System.out.println("doubles agree " + agree + "/" + total);
        Random f = new Random(4);
        System.out.println(f.nextFloat(10f) + " " + f.nextFloat(-1f, 1f) + " " + f.nextFloat(0.5f) + " " + f.nextInt(3, 9) + " " + f.nextLong(5) + " " + f.nextLong(-4, 4) + " " + f.nextDouble(2.0) + " " + f.nextDouble(-1, 1));
        attempt("nextInt(5, 5)", () -> new Random(1).nextInt(5, 5));
        attempt("nextInt(6, 5)", () -> new Random(1).nextInt(6, 5));
        attempt("nextLong(0)", () -> new Random(1).nextLong(0));
        attempt("nextLong(-1)", () -> new Random(1).nextLong(-1));
        attempt("nextLong(3, 3)", () -> new Random(1).nextLong(3, 3));
        attempt("nextDouble(0)", () -> new Random(1).nextDouble(0));
        attempt("nextDouble(-1)", () -> new Random(1).nextDouble(-1.0));
        attempt("nextDouble(NaN)", () -> new Random(1).nextDouble(Double.NaN));
        attempt("nextDouble(inf)", () -> new Random(1).nextDouble(Double.POSITIVE_INFINITY));
        attempt("nextDouble(1, 0)", () -> new Random(1).nextDouble(1, 0));
        attempt("nextDouble(-inf, 0)", () -> new Random(1).nextDouble(Double.NEGATIVE_INFINITY, 0));
        attempt("nextDouble(-max, max)", () -> new Random(1).nextDouble(-Double.MAX_VALUE, Double.MAX_VALUE));
        attempt("nextFloat(0)", () -> new Random(1).nextFloat(0f));
        attempt("nextFloat(2, 1)", () -> new Random(1).nextFloat(2f, 1f));
        attempt("nextFloat(-max, max)", () -> new Random(1).nextFloat(-Float.MAX_VALUE, Float.MAX_VALUE));
        attempt("nextBytes(null)", () -> {
            new Random(1).nextBytes(null);
            return null;
        });
        attempt("nextInt(0)", () -> new Random(1).nextInt(0));
        attempt("nextGaussian(1, 2)", () -> new Random(1).nextGaussian(1, 2));
        attempt("nextExponential", () -> new Random(1).nextExponential());
    }
}
