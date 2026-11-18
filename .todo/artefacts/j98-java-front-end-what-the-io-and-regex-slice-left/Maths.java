public class Maths {
    static final double[] DS = {0.0, -0.0, 1.0, -1.0, 0.5, 1.5, 2.5, -2.5, 1e-10, 1e10, 1e300, -1e300, 1e-320, Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_NORMAL, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 3.141592653589793, 123456.789};
    static final float[] FS = {0.0f, -0.0f, 1.0f, -1.5f, 1e-40f, Float.MIN_VALUE, Float.MAX_VALUE, Float.MIN_NORMAL, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 3.14f, 16777216f};
    static final int[] INTS = {0, 1, -1, 7, -7, Integer.MAX_VALUE, Integer.MIN_VALUE, 100, -100};
    static final long[] LONGS = {0L, 1L, -1L, 7L, -7L, Long.MAX_VALUE, Long.MIN_VALUE, 1L << 40, -(1L << 40), 0x123456789ABCDEFL};

    interface Op { Object run(); }

    static void t(String name, Op op) {
        try {
            System.out.println(name + " = " + op.run());
        } catch (RuntimeException e) {
            System.out.println(name + " ! " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        for (double d : DS) {
            System.out.println(d + ": " + Math.log1p(d) + " " + Math.expm1(d) + " " + Math.getExponent(d) + " "
                    + Double.toHexString(d) + " " + StrictMath.sqrt(d) + " " + StrictMath.sin(d) + " " + StrictMath.abs(d)
                    + " " + Math.signum(d));
            for (double e : new double[] {0.0, -0.0, 1.0, -3.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e300, 0.3}) {
                System.out.println("  " + e + ": " + Math.IEEEremainder(d, e) + " " + Math.nextAfter(d, e) + " "
                        + Math.copySign(d, e) + " " + StrictMath.pow(d, e) + " " + StrictMath.max(d, e) + " " + StrictMath.IEEEremainder(d, e));
            }
        }
        for (float f : FS) {
            System.out.println(f + ": " + Math.getExponent(f) + " " + Float.toHexString(f) + " " + Math.signum(f));
            for (double e : new double[] {0.0, -0.0, 1.0, -3.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e300, 3.14, 3.140000104904175}) {
                System.out.println("  " + e + ": " + Math.nextAfter(f, e) + " " + Math.copySign(f, (float) e));
            }
        }
        for (int a : INTS) {
            final int x = a;
            t("absExact " + a, () -> Math.absExact(x));
            t("incrementExact " + a, () -> Math.incrementExact(x));
            t("decrementExact " + a, () -> Math.decrementExact(x));
            t("negateExact " + a, () -> Math.negateExact(x));
            for (int b : INTS) {
                final int y = b;
                t("ceilDiv " + a + " " + b, () -> Math.ceilDiv(x, y));
                t("ceilMod " + a + " " + b, () -> Math.ceilMod(x, y));
                t("multiplyFull " + a + " " + b, () -> Math.multiplyFull(x, y));
                t("clamp " + a + " " + b, () -> Math.clamp((long) x, y, 50));
            }
        }
        for (long a : LONGS) {
            final long x = a;
            t("absExact " + a, () -> Math.absExact(x));
            t("incrementExact " + a, () -> Math.incrementExact(x));
            t("decrementExact " + a, () -> Math.decrementExact(x));
            t("negateExact " + a, () -> Math.negateExact(x));
            t("clampLI " + a, () -> Math.clamp(x, -5, 5));
            t("clampLL " + a, () -> Math.clamp(x, -5L, 1L << 41));
            for (long b : LONGS) {
                final long y = b;
                t("ceilDiv " + a + " " + b, () -> Math.ceilDiv(x, y));
                t("ceilMod " + a + " " + b, () -> Math.ceilMod(x, y));
                t("multiplyHigh " + a + " " + b, () -> Math.multiplyHigh(x, y));
                t("unsignedMultiplyHigh " + a + " " + b, () -> Math.unsignedMultiplyHigh(x, y));
            }
            for (int b : new int[] {1, -1, 7, -7, 0, Integer.MIN_VALUE}) {
                final int y = b;
                t("ceilDivLI " + a + " " + b, () -> Math.ceilDiv(x, y));
                t("ceilModLI " + a + " " + b, () -> Math.ceilMod(x, y));
                t("floorDivLI " + a + " " + b, () -> Math.floorDiv(x, y));
                t("floorModLI " + a + " " + b, () -> Math.floorMod(x, y));
                t("multiplyExactLI " + a + " " + b, () -> Math.multiplyExact(x, y));
            }
        }
        t("clamp min>max", () -> Math.clamp(1L, 5, 3));
        t("clampL min>max", () -> Math.clamp(1L, 5L, 3L));
        t("clampD min>max", () -> Math.clamp(1.0, 5.0, 3.0));
        t("clampF min>max", () -> Math.clamp(1.0f, 5.0f, 3.0f));
        t("clampD nan min", () -> Math.clamp(1.0, Double.NaN, 3.0));
        t("clampD nan max", () -> Math.clamp(1.0, 0.0, Double.NaN));
        t("clampF nan min", () -> Math.clamp(1.0f, Float.NaN, 3.0f));
        t("clampD nan v", () -> Math.clamp(Double.NaN, 0.0, 3.0));
        t("clampD -0", () -> Math.clamp(-0.0, 0.0, 3.0));
        t("clampD 0 -0", () -> Math.clamp(0.0, -3.0, -0.0));
        t("clampD -0.0 0.0", () -> Math.clamp(1.0, 0.0, -0.0));
        t("clampD", () -> Math.clamp(2.5, 0.0, 3.0) + " " + Math.clamp(-2.5, 0.0, 3.0) + " " + Math.clamp(22.5, 0.0, 3.0));
        t("clampF", () -> Math.clamp(2.5f, 0.0f, 3.0f) + " " + Math.clamp(-2.5f, 0.0f, 3.0f) + " " + Math.clamp(22.5f, 0.0f, 3.0f));
        System.out.println(Double.MIN_NORMAL + " " + Double.MAX_EXPONENT + " " + Double.MIN_EXPONENT + " " + Float.MAX_EXPONENT + " " + Float.MIN_EXPONENT + " " + Math.TAU + " " + StrictMath.PI + " " + StrictMath.E);
        System.out.println(StrictMath.floorMod(-7, 3) + " " + StrictMath.round(2.5) + " " + StrictMath.round(2.5f) + " " + StrictMath.abs(-3) + " " + StrictMath.max(1, 2) + " " + StrictMath.hypot(3, 4) + " " + StrictMath.cbrt(27) + " " + StrictMath.toRadians(180) + " " + StrictMath.addExact(1, 2) + " " + StrictMath.log1p(1e-10) + " " + StrictMath.clamp(5L, 1, 3) + " " + StrictMath.ceilDiv(7, 2));
    }
}
