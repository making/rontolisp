import java.util.*;
import java.util.stream.*;

// Which draws make a bounded element of Random's streams: candidates written from the
// API documentation, counted against a JDK over seeded ranges.
public class Rand {
    // A: the bound's nextInt plus the origin; a range wider than an int by rejection
    static int intA(Random r, int origin, int bound) {
        if (origin < bound) {
            int n = bound - origin;
            if (n > 0) {
                return r.nextInt(n) + origin;
            }
            int v;
            do {
                v = r.nextInt();
            }
            while (v < origin || v >= bound);
            return v;
        }
        return r.nextInt();
    }

    // B: one nextInt masked for a power of two, else its upper 31 bits reduced, rejecting what is over-represented
    static int intB(Random r, int origin, int bound) {
        int v = r.nextInt();
        if (origin < bound) {
            int n = bound - origin;
            int m = n - 1;
            if ((n & m) == 0) {
                v = (v & m) + origin;
            }
            else if (n > 0) {
                int u = v >>> 1;
                while (u + m - (v = u % n) < 0) {
                    u = r.nextInt() >>> 1;
                }
                v += origin;
            }
            else {
                while (v < origin || v >= bound) {
                    v = r.nextInt();
                }
            }
        }
        return v;
    }

    static long longB(Random r, long origin, long bound) {
        long v = r.nextLong();
        if (origin < bound) {
            long n = bound - origin;
            long m = n - 1;
            if ((n & m) == 0L) {
                v = (v & m) + origin;
            }
            else if (n > 0L) {
                long u = v >>> 1;
                while (u + m - (v = u % n) < 0L) {
                    u = r.nextLong() >>> 1;
                }
                v += origin;
            }
            else {
                while (v < origin || v >= bound) {
                    v = r.nextLong();
                }
            }
        }
        return v;
    }

    static double doubleA(Random r, double origin, double bound) {
        double v = r.nextDouble();
        if (origin < bound) {
            v = v * (bound - origin) + origin;
            if (v >= bound) {
                v = Math.nextDown(bound);
            }
        }
        return v;
    }

    public static void main(String[] args) {
        Random pick = new Random(42);
        int a = 0, b = 0, lb = 0, da = 0, plainI = 0, plainL = 0, plainD = 0, total = 0;
        for (int round = 0; round < 3000; round++) {
            long seed = pick.nextLong();
            int kind = round % 6;
            int origin = kind == 0 ? 0 : kind == 1 ? -pick.nextInt(1000) : kind == 2 ? pick.nextInt() : kind == 3 ? Integer.MIN_VALUE : kind == 4 ? pick.nextInt(100) : -5;
            int bound = kind == 0 ? 1 + pick.nextInt(1000) : kind == 1 ? 1 + pick.nextInt(1 << 20) : kind == 2 ? origin + 1 + pick.nextInt(Integer.MAX_VALUE) : kind == 3 ? Integer.MAX_VALUE : kind == 4 ? origin + (1 << pick.nextInt(20)) : 5;
            if (origin >= bound) {
                continue;
            }
            total++;
            int[] jdk = new Random(seed).ints(8, origin, bound).toArray();
            Random ra = new Random(seed), rb = new Random(seed);
            boolean okA = true, okB = true;
            for (int i = 0; i < 8; i++) {
                okA &= intA(ra, origin, bound) == jdk[i];
                okB &= intB(rb, origin, bound) == jdk[i];
            }
            a += okA ? 1 : 0;
            b += okB ? 1 : 0;
            long lo = kind == 3 ? Long.MIN_VALUE : origin * (kind == 2 ? 1L << 20 : 1L);
            long hi = kind == 3 ? Long.MAX_VALUE : kind == 4 ? lo + (1L << pick.nextInt(50)) : bound * (kind == 2 ? 1L << 31 : 1L);
            if (lo < hi) {
                long[] jl = new Random(seed).longs(8, lo, hi).toArray();
                Random rl = new Random(seed);
                boolean okL = true;
                for (int i = 0; i < 8; i++) {
                    okL &= longB(rl, lo, hi) == jl[i];
                }
                lb += okL ? 1 : 0;
            }
            else {
                lb++;
            }
            double dlo = origin / 7.0, dhi = bound / 3.0 + 1;
            if (!(dlo < dhi)) {
                dhi = dlo + 1;
            }
            double[] jd = new Random(seed).doubles(8, dlo, dhi).toArray();
            Random rd = new Random(seed);
            boolean okD = true;
            for (int i = 0; i < 8; i++) {
                okD &= Double.doubleToLongBits(doubleA(rd, dlo, dhi)) == Double.doubleToLongBits(jd[i]);
            }
            da += okD ? 1 : 0;
            Random p1 = new Random(seed), p2 = new Random(seed), p3 = new Random(seed);
            plainI += Arrays.equals(new Random(seed).ints(5).toArray(), new int[] { p1.nextInt(), p1.nextInt(), p1.nextInt(), p1.nextInt(), p1.nextInt() }) ? 1 : 0;
            plainL += Arrays.equals(new Random(seed).longs(3).toArray(), new long[] { p2.nextLong(), p2.nextLong(), p2.nextLong() }) ? 1 : 0;
            plainD += Arrays.equals(new Random(seed).doubles(3).toArray(), new double[] { p3.nextDouble(), p3.nextDouble(), p3.nextDouble() }) ? 1 : 0;
        }
        System.out.println("of " + total + ": ints A " + a + ", ints B " + b + ", longs B " + lb + ", doubles A " + da + ", plain " + plainI + " " + plainL + " " + plainD);
        // the refusals
        String[] names = { "ints(-1)", "ints(5, 5)", "ints(-1, 0, 1)", "ints(1, 2, 1)", "longs(-1)", "longs(3, 3)", "longs(-2, 0, 1)", "doubles(-1)", "doubles(1.0, 1.0)", "doubles(0, NaN)", "doubles(-1, 0, 1)", "doubles(0, inf)", "doubles(-inf, 0)" };
        Runnable[] calls = { () -> new Random(1).ints(-1), () -> new Random(1).ints(5, 5), () -> new Random(1).ints(-1, 0, 1), () -> new Random(1).ints(1, 2, 1), () -> new Random(1).longs(-1), () -> new Random(1).longs(3, 3), () -> new Random(1).longs(-2, 0, 1), () -> new Random(1).doubles(-1), () -> new Random(1).doubles(1.0, 1.0), () -> new Random(1).doubles(0, Double.NaN), () -> new Random(1).doubles(-1, 0, 1), () -> new Random(1).doubles(0, Double.POSITIVE_INFINITY).limit(1).sum(), () -> new Random(1).doubles(Double.NEGATIVE_INFINITY, 0).limit(1).sum() };
        for (int i = 0; i < names.length; i++) {
            try {
                calls[i].run();
                System.out.println(names[i] + ": ran");
            }
            catch (RuntimeException e) {
                System.out.println(names[i] + ": " + e);
            }
        }
        System.out.println(new Random(7).ints(0).count() + " " + new Random(7).ints(3).count() + " " + new Random(7).ints().count() + " " + new Random(7).ints().limit(4).count() + " " + new Random(7).ints(5).skip(2).count());
        Random shared = new Random(3);
        IntStream lazy = shared.ints(2);
        int first = shared.nextInt();
        System.out.println("lazy: " + (lazy.boxed().toList().contains(first)) + " " + new Random(3).ints(3).boxed().toList().get(1).equals(new Random(3).ints(2).skip(1).findFirst().getAsInt()));
        System.out.println(new Random(5).ints(3, 0, 10).boxed().toList() + " " + new Random(5).longs(2, -5, 5).boxed().toList() + " " + new Random(5).doubles(2, 1, 2).boxed().toList() + " " + new Random(5).doubles(2).boxed().toList() + " " + new Random(5).longs(1).boxed().toList());
        System.out.println(new Random(5).ints().isParallel() + " " + new Random(5).ints(3).sorted().boxed().toList() + " " + new Random(5).doubles().limit(2).count());
    }
}
