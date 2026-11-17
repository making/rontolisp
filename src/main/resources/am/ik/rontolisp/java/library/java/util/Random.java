// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// The generator the API specification states: a linear congruential formula over 48
// bits, and each method the combination of its draws the specification gives.
public class Random {

    private long state;

    private double spare;

    private boolean hasSpare;

    private static long entropy = 8682522807148012L;

    public Random() {
        entropy = entropy * 1181783497276652981L;
        setSeed(entropy ^ System.nanoTime());
    }

    public Random(long seed) {
        setSeed(seed);
    }

    public void setSeed(long seed) {
        state = (seed ^ 0x5DEECE66DL) & ((1L << 48) - 1);
        hasSpare = false;
    }

    protected int next(int bits) {
        state = (state * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
        return (int) (state >>> (48 - bits));
    }

    public int nextInt() {
        return next(32);
    }

    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        if ((bound & -bound) == bound) {
            return (int) ((bound * (long) next(31)) >> 31);
        }
        int bits;
        int value;
        do {
            bits = next(31);
            value = bits % bound;
        }
        while (bits - value + (bound - 1) < 0);
        return value;
    }

    public long nextLong() {
        return ((long) next(32) << 32) + next(32);
    }

    public boolean nextBoolean() {
        return next(1) != 0;
    }

    public double nextDouble() {
        return (((long) next(26) << 27) + next(27)) / 9007199254740992.0;
    }

    public double nextGaussian() {
        if (hasSpare) {
            hasSpare = false;
            return spare;
        }
        double v1;
        double v2;
        double s;
        do {
            v1 = 2 * nextDouble() - 1;
            v2 = 2 * nextDouble() - 1;
            s = v1 * v1 + v2 * v2;
        }
        while (s >= 1 || s == 0);
        double multiplier = Math.sqrt(-2 * Math.log(s) / s);
        spare = v2 * multiplier;
        hasSpare = true;
        return v1 * multiplier;
    }
}
