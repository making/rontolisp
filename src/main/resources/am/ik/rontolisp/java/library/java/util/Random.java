// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.io.Serializable;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import rontolisp.internal.DoubleFlow;
import rontolisp.internal.IntFlow;
import rontolisp.internal.LongFlow;

// The generator the API specification states: a linear congruential formula over 48
// bits, and each method the combination of its draws the specification gives.
public class Random implements Serializable {

    private long state;

    private double spare;

    private boolean hasSpare;

    private static long distinct;

    // No seed given: the specification asks only for one unlikely to repeat. Each call
    // steps a counter by an odd 64-bit constant (2^64 over the golden ratio, so every
    // step lands on a new value) and mixes the clock in.
    public Random() {
        distinct = distinct + 0x9E3779B97F4A7C15L;
        setSeed(distinct ^ System.nanoTime());
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

    // The bytes of one nextInt() after another, the lowest byte first.
    public void nextBytes(byte[] bytes) {
        int word = 0;
        for (int i = 0; i < bytes.length; i++) {
            if ((i & 3) == 0) {
                word = nextInt();
            }
            bytes[i] = (byte) word;
            word >>= 8;
        }
    }

    public float nextFloat() {
        return next(24) / 16777216.0f;
    }

    // A number between two bounds is drawn as an element of a stream between them is.
    public int nextInt(int origin, int bound) {
        ranged(origin < bound);
        return between(origin, bound);
    }

    public long nextLong(long bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return between(0L, bound);
    }

    public long nextLong(long origin, long bound) {
        ranged(origin < bound);
        return between(origin, bound);
    }

    public double nextDouble(double bound) {
        if (!(bound > 0 && bound < Double.POSITIVE_INFINITY)) {
            throw new IllegalArgumentException("bound must be finite and positive");
        }
        return between(0.0, bound);
    }

    // Bounds too far apart for a double, which a stream refuses, are taken at half their
    // values and the draw doubled: JDK 25's numbers on 14,000 seeded draws, where scaling
    // the draw by each bound gave 7,417.
    public double nextDouble(double origin, double bound) {
        ranged(origin < bound && origin > Double.NEGATIVE_INFINITY && bound < Double.POSITIVE_INFINITY);
        if (bound - origin < Double.POSITIVE_INFINITY) {
            return between(origin, bound);
        }
        double drawn = (nextDouble() * (bound / 2 - origin / 2) + origin / 2) * 2;
        return drawn >= bound ? Math.nextDown(bound) : drawn;
    }

    public float nextFloat(float bound) {
        if (!(bound > 0 && bound < Float.POSITIVE_INFINITY)) {
            throw new IllegalArgumentException("bound must be finite and positive");
        }
        float drawn = nextFloat() * bound;
        return drawn >= bound ? Math.nextDown(bound) : drawn;
    }

    // Bounds too far apart for a float are taken at half their values and the draw
    // doubled, as a double's are (JDK 25's numbers on 10,000 seeded draws).
    public float nextFloat(float origin, float bound) {
        ranged(origin < bound && origin > Float.NEGATIVE_INFINITY && bound < Float.POSITIVE_INFINITY);
        float drawn = nextFloat();
        drawn = bound - origin < Float.POSITIVE_INFINITY ? drawn * (bound - origin) + origin
                : (drawn * (bound / 2 - origin / 2) + origin / 2) * 2;
        return drawn >= bound ? Math.nextDown(bound) : drawn;
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

    // The streams: each element is drawn when the stream is read, a stream with no size
    // given holds as many as a long counts.
    private static void sized(long streamSize) {
        if (streamSize < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
    }

    private static void ranged(boolean bounded) {
        if (!bounded) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
    }

    public IntStream ints() {
        return IntFlow.drawn(() -> nextInt(), Long.MAX_VALUE);
    }

    public IntStream ints(long streamSize) {
        sized(streamSize);
        return IntFlow.drawn(() -> nextInt(), streamSize);
    }

    public IntStream ints(int randomNumberOrigin, int randomNumberBound) {
        return ints(Long.MAX_VALUE, randomNumberOrigin, randomNumberBound);
    }

    public IntStream ints(long streamSize, int randomNumberOrigin, int randomNumberBound) {
        sized(streamSize);
        ranged(randomNumberOrigin < randomNumberBound);
        return IntFlow.drawn(() -> between(randomNumberOrigin, randomNumberBound), streamSize);
    }

    public LongStream longs() {
        return LongFlow.drawn(() -> nextLong(), Long.MAX_VALUE);
    }

    public LongStream longs(long streamSize) {
        sized(streamSize);
        return LongFlow.drawn(() -> nextLong(), streamSize);
    }

    public LongStream longs(long randomNumberOrigin, long randomNumberBound) {
        return longs(Long.MAX_VALUE, randomNumberOrigin, randomNumberBound);
    }

    public LongStream longs(long streamSize, long randomNumberOrigin, long randomNumberBound) {
        sized(streamSize);
        ranged(randomNumberOrigin < randomNumberBound);
        return LongFlow.drawn(() -> between(randomNumberOrigin, randomNumberBound), streamSize);
    }

    public DoubleStream doubles() {
        return DoubleFlow.drawn(() -> nextDouble(), Long.MAX_VALUE);
    }

    public DoubleStream doubles(long streamSize) {
        sized(streamSize);
        return DoubleFlow.drawn(() -> nextDouble(), streamSize);
    }

    public DoubleStream doubles(double randomNumberOrigin, double randomNumberBound) {
        return doubles(Long.MAX_VALUE, randomNumberOrigin, randomNumberBound);
    }

    public DoubleStream doubles(long streamSize, double randomNumberOrigin, double randomNumberBound) {
        sized(streamSize);
        ranged(randomNumberOrigin < randomNumberBound && randomNumberBound - randomNumberOrigin < Double.POSITIVE_INFINITY);
        return DoubleFlow.drawn(() -> between(randomNumberOrigin, randomNumberBound), streamSize);
    }

    // One number of a stream between two bounds. The documentation gives the draws for a
    // long: one nextLong, masked where the width is a power of two; else its upper 63
    // bits reduced by the width, drawn again while they fall in the part of the range the
    // width does not divide; a width no long holds by drawing until one is within. For
    // an int no documentation says which: of nextInt of the width moved by the origin
    // and the long's draws over nextInt, the second gave a JDK 25's numbers on all 2,891
    // seeded ranges asked, the first on 2,414.
    private int between(int origin, int bound) {
        int drawn = nextInt();
        int width = bound - origin;
        int most = width - 1;
        if ((width & most) == 0) {
            return (drawn & most) + origin;
        }
        if (width > 0) {
            int upper = drawn >>> 1;
            int within = upper % width;
            while (upper + most - within < 0) {
                upper = nextInt() >>> 1;
                within = upper % width;
            }
            return within + origin;
        }
        while (drawn < origin || drawn >= bound) {
            drawn = nextInt();
        }
        return drawn;
    }

    private long between(long origin, long bound) {
        long drawn = nextLong();
        long width = bound - origin;
        long most = width - 1;
        if ((width & most) == 0) {
            return (drawn & most) + origin;
        }
        if (width > 0) {
            long upper = drawn >>> 1;
            long within = upper % width;
            while (upper + most - within < 0) {
                upper = nextLong() >>> 1;
                within = upper % width;
            }
            return within + origin;
        }
        while (drawn < origin || drawn >= bound) {
            drawn = nextLong();
        }
        return drawn;
    }

    // a rounding that reaches the bound answers the double below it
    private double between(double origin, double bound) {
        double drawn = nextDouble() * (bound - origin) + origin;
        return drawn >= bound ? Math.nextDown(bound) : drawn;
    }
}
