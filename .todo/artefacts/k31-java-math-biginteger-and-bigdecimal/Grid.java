import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.function.Supplier;

// The probe k31 learned java.math from: every line is what a JDK 25 prints, and the
// interpreter, the JVM, wasm and the component print the same lines but those the
// README beside this file names. Written here; nothing of a JDK is in it.
public class Grid {

    static long seed = 20261120L;

    static int next(int bound) {
        seed = (seed * 6364136223846793005L + 1442695040888963407L);
        return (int) ((seed >>> 33) % bound);
    }

    static String shown(Object value) {
        if (value instanceof Object[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof byte[] bytes) {
            return Arrays.toString(bytes);
        }
        return String.valueOf(value);
    }

    static void t(String label, Supplier<Object> made) {
        String text;
        try {
            text = shown(made.get());
        }
        catch (NullPointerException e) {
            text = "! NullPointerException";
        }
        catch (RuntimeException e) {
            text = "! " + e.getClass().getName() + ": " + e.getMessage();
        }
        System.out.println(label + " = " + text);
    }

    static final String[] INTEGERS = {"0", "1", "-1", "2", "7", "-8", "10", "255", "-256", "2147483647",
        "-2147483648", "2147483648", "4294967297", "9223372036854775807", "-9223372036854775808",
        "18446744073709551616", "100000000000000000000", "-999999999999999999999999999993",
        "123456789012345678901234567890", "340282366920938463463374607431768211455"};

    static final int[] SHIFTS = {-70, -3, 0, 1, 31, 33, 64, 70};

    static void integers() {
        for (String text : INTEGERS) {
            BigInteger a = new BigInteger(text);
            t(text + " unary", () -> a.negate() + " " + a.abs() + " " + a.signum() + " " + a.not() + " "
                    + a.bitLength() + " " + a.bitCount() + " " + a.getLowestSetBit() + " " + a.hashCode());
            t(text + " radix", () -> a.toString(2) + " " + a.toString(8) + " " + a.toString(16) + " "
                    + a.toString(36) + " " + a.toString(7));
            t(text + " bytes", () -> shown(a.toByteArray()) + " " + new BigInteger(a.toByteArray()) + " "
                    + new BigInteger(a.signum(), a.abs().toByteArray()));
            t(text + " primitive", () -> a.intValue() + " " + a.longValue() + " " + a.floatValue() + " "
                    + a.doubleValue() + " " + a.shortValue() + " " + a.byteValue());
            t(text + " longValueExact", () -> a.longValueExact());
            t(text + " intValueExact", () -> a.intValueExact());
            t(text + " shortValueExact", () -> a.shortValueExact());
            t(text + " byteValueExact", () -> a.byteValueExact());
            t(text + " sqrt", () -> shown(a.sqrtAndRemainder()) + " " + a.sqrt());
            t(text + " pow", () -> a.pow(0) + " " + a.pow(1) + " " + a.pow(2) + " " + a.pow(5));
            for (int shift : SHIFTS) {
                t(text + " shift " + shift, () -> a.shiftLeft(shift) + " " + a.shiftRight(shift));
            }
            for (int bit : new int[] {0, 5, 31, 64, 100}) {
                t(text + " bit " + bit, () -> a.testBit(bit) + " " + a.setBit(bit) + " " + a.clearBit(bit) + " "
                        + a.flipBit(bit));
            }
            for (String other : INTEGERS) {
                BigInteger b = new BigInteger(other);
                String pair = text + " " + other;
                t(pair + " sums", () -> a.add(b) + " " + a.subtract(b) + " " + a.multiply(b));
                t(pair + " divide", () -> a.divide(b) + " " + a.remainder(b) + " "
                        + shown(a.divideAndRemainder(b)));
                t(pair + " mod", () -> a.mod(b));
                t(pair + " bits", () -> a.gcd(b) + " " + a.and(b) + " " + a.or(b) + " " + a.xor(b) + " "
                        + a.andNot(b));
                t(pair + " order", () -> a.compareTo(b) + " " + a.equals(b) + " " + a.min(b) + " " + a.max(b));
                t(pair + " modPow", () -> a.modPow(BigInteger.valueOf(65537), b));
                t(pair + " modPow-", () -> a.modPow(BigInteger.valueOf(-3), b));
                t(pair + " modInverse", () -> a.modInverse(b));
            }
        }
        t("constants", () -> BigInteger.ZERO + " " + BigInteger.ONE + " " + BigInteger.TWO + " " + BigInteger.TEN
                + " " + BigInteger.valueOf(Long.MIN_VALUE) + " " + BigInteger.valueOf(-5).equals(-5L));
        String[] texts = {"", "+", "-", "12x", "+5", "-0", "1-2", "--1", "+-1", "1+", " 1",
            "123456789012345678901234567890x1234567890", "x23456789012345678901234567890",
            "0000000000000000000000x1", "-0000000000000000000000012", "-123x234567890"};
        for (String text : texts) {
            t("new \"" + text + "\"", () -> new BigInteger(text));
        }
        t("radix 1", () -> new BigInteger("1", 1));
        t("radix 37", () -> new BigInteger("", 37));
        t("radix 16", () -> new BigInteger("-fF", 16) + " " + new BigInteger("zz", 36));
        t("radix 8", () -> new BigInteger("19", 8));
        t("radix 16 group", () -> new BigInteger("123456789abcdefg123456789", 16));
        t("radix 2 group", () -> new BigInteger("10101010101010101010101010101010101010101010101210101010", 2));
        t("radix 36 group", () -> new BigInteger("zzzzzzzzzzzz!zzzzzzzzzzzzzzzzz", 36));
        t("bytes none", () -> new BigInteger(new byte[0]));
        t("bytes range", () -> new BigInteger(new byte[] {1, 2, 3}, 1, 2) + " " + new BigInteger(new byte[] {1, 2, 3}, 1, 0));
        t("bytes outside", () -> new BigInteger(new byte[] {1, 2, 3}, 2, 2));
        t("signum 2", () -> new BigInteger(2, new byte[] {1}));
        t("signum 0", () -> new BigInteger(0, new byte[] {1}));
        t("signum -1", () -> new BigInteger(-1, new byte[] {1, 0}) + " " + new BigInteger(0, new byte[] {0, 0}) + " "
                + new BigInteger(1, new byte[0]));
        t("pow -1", () -> BigInteger.TEN.pow(-1));
        t("bit -1", () -> BigInteger.TEN.testBit(-1));
        t("set -1", () -> BigInteger.TEN.setBit(-1));
        t("null", () -> BigInteger.TEN.add(null));
        // seeded: numbers of up to 40 digits
        for (int i = 0; i < 150; i++) {
            BigInteger a = random();
            BigInteger b = random();
            int exponent = next(6);
            t("random " + i, () -> a + " " + b + " " + a.add(b) + " " + a.multiply(b) + " " + a.gcd(b) + " "
                    + a.pow(exponent) + " " + a.xor(b) + " " + a.hashCode() + " " + a.doubleValue() + " "
                    + a.floatValue() + " " + a.abs().sqrt() + " " + a.compareTo(b));
            t("random " + i + " divide", () -> shown(a.divideAndRemainder(b)) + " " + a.mod(b.abs()) + " "
                    + a.modPow(b.abs(), a.abs().add(BigInteger.TWO)));
        }
    }

    static BigInteger random() {
        int length = 1 + next(40);
        StringBuilder digits = new StringBuilder(next(3) == 0 ? "-" : "");
        for (int i = 0; i < length; i++) {
            digits.append((char) ('0' + next(10)));
        }
        return new BigInteger(digits.toString());
    }

    static final String[] DECIMALS = {"0", "0.00", "0E+3", "1", "-1", "1.0", "1.00", "10", "100", "1E+2", "0.1",
        "0.5", "-0.5", "1.5", "2.5", "-2.5", "0.001", "123.456", "-123.456", "999.999", "1E-10", "1E+10",
        "12345678901234567890.123456789", "0.000001", "0.0000001", "3", "7", "1.005", "33.3333", "5E-7",
        "1.23E+5", "-0.045", "9.5", "0.125", "64", "2", "1000000", "4.00", "0.25"};

    static final MathContext[] CONTEXTS = {new MathContext(1), new MathContext(2, RoundingMode.HALF_EVEN),
        new MathContext(3, RoundingMode.DOWN), new MathContext(5, RoundingMode.CEILING), MathContext.DECIMAL32,
        MathContext.DECIMAL64, new MathContext(4, RoundingMode.UP), new MathContext(6, RoundingMode.FLOOR),
        new MathContext(2, RoundingMode.HALF_DOWN), MathContext.UNLIMITED};

    static void decimals() {
        for (String text : DECIMALS) {
            BigDecimal a = new BigDecimal(text);
            t(text + " text", () -> a + " " + a.toEngineeringString() + " " + a.toPlainString() + " " + a.scale()
                    + " " + a.precision() + " " + a.unscaledValue() + " " + a.signum() + " " + a.hashCode());
            t(text + " unary", () -> a.negate() + " " + a.abs() + " " + a.plus() + " " + a.stripTrailingZeros() + " "
                    + a.ulp() + " " + a.toBigInteger());
            t(text + " primitive", () -> a.intValue() + " " + a.longValue() + " " + a.doubleValue() + " "
                    + a.floatValue());
            t(text + " toBigIntegerExact", () -> a.toBigIntegerExact());
            t(text + " longValueExact", () -> a.longValueExact());
            t(text + " intValueExact", () -> a.intValueExact());
            t(text + " shortValueExact", () -> a.shortValueExact());
            t(text + " byteValueExact", () -> a.byteValueExact());
            for (int scale : new int[] {-2, 0, 1, 2, 5}) {
                for (RoundingMode mode : RoundingMode.values()) {
                    t(text + " setScale " + scale + " " + mode, () -> a.setScale(scale, mode));
                }
            }
            for (int precision : new int[] {1, 2, 3, 5}) {
                for (RoundingMode mode : RoundingMode.values()) {
                    MathContext context = new MathContext(precision, mode);
                    t(text + " round " + precision + " " + mode,
                            () -> a.round(context) + " " + a.negate(context) + " " + a.abs(context));
                    t(text + " sqrt " + precision + " " + mode, () -> a.sqrt(context));
                }
            }
            t(text + " sqrt", () -> a.sqrt(MathContext.DECIMAL64) + " " + a.sqrt(MathContext.DECIMAL128));
            t(text + " sqrt all", () -> a.sqrt(MathContext.UNLIMITED));
            for (int places = -3; places <= 3; places++) {
                int n = places;
                t(text + " move " + n,
                        () -> a.movePointLeft(n) + " " + a.movePointRight(n) + " " + a.scaleByPowerOfTen(n));
            }
            t(text + " pow", () -> a.pow(0) + " " + a.pow(1) + " " + a.pow(2) + " " + a.pow(3) + " " + a.pow(7));
            for (int n : new int[] {-3, -1, 0, 1, 2, 3, 5, 10, 100, 12345}) {
                for (int c = 0; c < 6; c++) {
                    MathContext context = CONTEXTS[c];
                    t(text + " pow " + n + " " + context, () -> a.pow(n, context));
                }
            }
            for (String other : DECIMALS) {
                BigDecimal b = new BigDecimal(other);
                String pair = text + " " + other;
                t(pair + " sums", () -> a.add(b) + " " + a.subtract(b) + " " + a.multiply(b));
                t(pair + " divide", () -> a.divide(b));
                t(pair + " whole", () -> a.divideToIntegralValue(b) + " " + a.remainder(b) + " "
                        + shown(a.divideAndRemainder(b)));
                t(pair + " order", () -> a.compareTo(b) + " " + a.equals(b) + " " + a.min(b) + " " + a.max(b));
                for (RoundingMode mode : RoundingMode.values()) {
                    t(pair + " divide " + mode, () -> a.divide(b, mode) + " " + a.divide(b, 3, mode) + " "
                            + a.divide(b, -1, mode));
                }
                for (MathContext context : CONTEXTS) {
                    String at = pair + " " + context.getPrecision() + " " + context.getRoundingMode();
                    t(at + " sums", () -> a.add(b, context) + " " + a.subtract(b, context) + " "
                            + a.multiply(b, context));
                    t(at + " divide", () -> a.divide(b, context));
                    t(at + " whole", () -> a.divideToIntegralValue(b, context));
                    t(at + " remainder", () -> a.remainder(b, context));
                }
            }
        }
    }

    static void made() {
        String[] texts = {"", " ", "+", "-", ".", "+.", "1.", ".5", "-.5", "1e", "1e+", "1e-", "1e1", "1E+1", "1e-1",
            "e1", ".e1", "1.e1", "1..2", "1.2.3", "abc", "1a", "1 ", " 1", "1e1e1", "1e1.5", "+-1", "--1", "1-",
            "1e--1", "1e+-1", "0x10", "1_000", "1,000", "1e2147483647", "1e2147483648", "1e-2147483647",
            "1e-2147483648", "0e2147483648", "0.0e2147483647", "1.5e-2147483647", "1e0000000000000000001",
            "1e99999999999", "1e9999999999", "0e99999999999", "1e99999999999x", "1ex9999999999", "1e+0", "1e-0",
            "00012.3400", "-0", "-0.00", "0E+3", "0E-3", "12345678901234567890.12345678901234567890", "1d", "NaN",
            "Infinity", "e", ".e", "ex", "e1x", "+e", "e+", "1e1 ", "1e+x", "x.", "1.x", "..", "1e.5", "1.5e", "++1",
            "1e1+1", "0000", "0.", ".0", "-.0e-0", "000.000e+000", "1e000000000012", "1e0123456789012",
            "1e00000000000x", "1e+00x"};
        for (String text : texts) {
            t("new \"" + text + "\"", () -> new BigDecimal(text));
        }
        t("chars", () -> new BigDecimal(new char[] {'1', '.', '5'}) + " "
                + new BigDecimal(new char[] {'x', '1', '.', '5', 'y'}, 1, 3));
        t("chars outside", () -> new BigDecimal(new char[] {'x', '1', '.', '5', 'y'}, 3, 3));
        t("chars none", () -> new BigDecimal(new char[] {'x', '1', '.', '5', 'y'}, 1, 0));
        t("null", () -> new BigDecimal((String) null));
        double[] doubles = {0.1, -0.0, 1e300, 4.9e-324, 2.5, 100.0, 1.0 / 3, 123.456, 1e-5, 1.7976931348623157e308,
            0.1f, 16777216.0, 9007199254740993.0, -1e21, 1e22, 1e23, 5e-324, 0.5, 3.0, 1e7, 1.0E-4, 12345.678e10};
        for (double d : doubles) {
            t("double " + d, () -> new BigDecimal(d) + " " + BigDecimal.valueOf(d) + " "
                    + new BigDecimal(d, MathContext.DECIMAL32));
        }
        t("double NaN", () -> new BigDecimal(Double.NaN));
        t("double infinite", () -> new BigDecimal(Double.NEGATIVE_INFINITY));
        t("valueOf NaN", () -> BigDecimal.valueOf(Double.NaN));
        t("valueOf", () -> BigDecimal.valueOf(5L) + " " + BigDecimal.valueOf(5L, 2) + " " + BigDecimal.valueOf(5L, -2)
                + " " + BigDecimal.valueOf(Long.MIN_VALUE, 3) + " " + new BigDecimal(7) + " " + new BigDecimal(-7L));
        t("rounded", () -> new BigDecimal(123456, new MathContext(3)) + " "
                + new BigDecimal("123456", new MathContext(3, RoundingMode.DOWN)) + " "
                + new BigDecimal(BigInteger.valueOf(123456), 2, new MathContext(3)) + " "
                + new BigDecimal(BigInteger.valueOf(999999), new MathContext(2)) + " "
                + new BigDecimal(99999L, new MathContext(2)));
        t("constants", () -> BigDecimal.ZERO + " " + BigDecimal.ONE + " " + BigDecimal.TWO + " " + BigDecimal.TEN);
        t("contexts", () -> MathContext.DECIMAL128 + "|" + MathContext.UNLIMITED + "|" + MathContext.DECIMAL32 + "|"
                + MathContext.DECIMAL64 + "|" + new MathContext(4) + "|"
                + new MathContext("precision=7 roundingMode=HALF_EVEN") + "|"
                + new MathContext("precision=+7 roundingMode=HALF_UP").getPrecision() + "|"
                + new MathContext(4).equals(new MathContext(4, RoundingMode.HALF_UP)) + "|"
                + new MathContext(4).equals(new MathContext(4, RoundingMode.HALF_EVEN)) + "|"
                + MathContext.DECIMAL64.getRoundingMode());
        String[] contexts = {"precision=7", "precision=x roundingMode=HALF_EVEN", "precision=7 roundingMode=HALF",
            "precision=-7 roundingMode=HALF_UP", " precision=7 roundingMode=HALF_UP",
            "precision=7  roundingMode=HALF_UP", ""};
        for (String text : contexts) {
            t("context \"" + text + "\"", () -> new MathContext(text));
        }
        t("context -1", () -> new MathContext(-1));
        t("context null", () -> new MathContext(1, null).toString());
        t("modes", () -> shown(RoundingMode.values()) + " " + RoundingMode.valueOf(0) + " " + RoundingMode.valueOf(6)
                + " " + RoundingMode.valueOf("HALF_DOWN") + " " + RoundingMode.HALF_EVEN.ordinal());
        t("mode 8", () -> RoundingMode.valueOf(8));
        t("mode HALF", () -> RoundingMode.valueOf("HALF"));
    }

    static BigDecimal d(String text) {
        return new BigDecimal(text);
    }

    static void edges() {
        t("1/0 at a scale", () -> d("1").divide(d("0"), 2, RoundingMode.HALF_UP));
        t("0/0 at a scale", () -> d("0").divide(d("0"), 2, RoundingMode.HALF_UP));
        t("scale past the largest", () -> d("1e-2147483647").multiply(d("1e-10")));
        t("scale past the least", () -> d("1e2147483647").multiply(d("1e10")));
        t("zero's scale", () -> d("0e-2147483647").multiply(d("0e-10")) + " " + d("0e2147483647").multiply(d("0e10")));
        t("moved past", () -> d("1").movePointLeft(Integer.MAX_VALUE).movePointLeft(1));
        t("scaled past", () -> d("1e-2147483647").scaleByPowerOfTen(-10));
        t("pow -1", () -> d("2").pow(-1));
        t("pow 1000000000", () -> d("1").pow(1000000000));
        t("pow 999999999", () -> d("1").pow(999999999) + " " + d("-1").pow(999999999));
        t("pow mc 1000000000", () -> d("2").pow(1000000000, new MathContext(5)));
        t("pow mc digits", () -> d("2").pow(123456, new MathContext(3)));
        t("pow mc 0^0", () -> d("0").pow(0, new MathContext(3)) + " " + d("0").pow(0));
        t("pow mc 0^-1", () -> d("0").pow(-1, new MathContext(3)));
        t("far apart", () -> d("1e400").doubleValue() + " " + d("-1e400").doubleValue() + " " + d("1e-400").doubleValue()
                + " " + d("-1e-400").doubleValue() + " " + d("1e400").floatValue() + " " + d("1e-50").floatValue() + " "
                + d("-0.0").doubleValue() + " " + d("1e1000000000").doubleValue() + " "
                + d("1e-1000000000").floatValue());
        t("doubles", () -> d("0.1").doubleValue() + " " + d("0.1").floatValue() + " "
                + d("9007199254740993").doubleValue() + " " + d("1.7976931348623158e308").doubleValue() + " "
                + d("1.7976931348623159e308").doubleValue() + " " + d("2.4703282292062328e-324").doubleValue() + " "
                + d("2.4703282292062327e-324").doubleValue() + " " + d("16777217").floatValue() + " "
                + d("3.4028235677973366e38").floatValue() + " " + d("123456789.123456789e-3").doubleValue());
        t("low bits", () -> d("1e19").longValue() + " " + d("-1.9").longValue() + " "
                + d("18446744073709551617.5").longValue() + " " + d("1e-5").intValue() + " "
                + d("4294967297.9").intValue() + " " + d("1e1000").longValue() + " " + d("-1e63").longValue() + " "
                + d("123e62").longValue());
        String[] exact = {"1e19", "1.5", "0.5", "1e1000000", "1.0", "9223372036854775807", "9223372036854775808",
            "-9223372036854775808", "-9223372036854775809", "9223372036854775808.5", "10000000000000000000.5",
            "4294967296.5", "2147483648", "-2147483648", "32768", "-32768.0", "128", "-128", "1e-1000000000",
            "100e-2", "1.00e2"};
        for (String text : exact) {
            t(text + " longValueExact", () -> d(text).longValueExact());
            t(text + " intValueExact", () -> d(text).intValueExact());
            t(text + " shortValueExact", () -> d(text).shortValueExact());
            t(text + " byteValueExact", () -> d(text).byteValueExact());
        }
        t("compare far", () -> d("1e1000000000").compareTo(d("1")) + " " + d("1").compareTo(d("1e1000000000")) + " "
                + d("-1e1000000000").compareTo(d("1e-1000000000")) + " " + d("1e-1000000000").compareTo(d("0")) + " "
                + d("1e1000000000").compareTo(d("10e999999999")) + " " + d("1e1000000000").equals(d("10e999999999"))
                + " " + d("-1e1000000000").min(d("3")) + " " + d("2.0").equals(d("2.00")) + " " + d("2.0").equals("2.0"));
        // a number far below the other's last digit: every mode, both signs
        String[][] apart = {{"1E+50", "1"}, {"1", "1E-50"}, {"1", "-1E-50"}, {"-1", "1E-50"}, {"1E+50", "-1E-3"},
            {"1.5", "1E-30"}, {"2.5", "-1E-30"}, {"0.5", "1E-30"}, {"1000", "-1E-1000000000"},
            {"1E+1000000000", "1"}, {"999", "1E-40"}, {"-999.5", "-1E-40"}, {"12345", "1E-7"}, {"12345", "-1E-7"},
            {"1.00000", "1E-9"}, {"1E+3", "1E-9"}, {"1E+3", "-1E-9"}, {"1E+2", "1"}, {"1E+2", "-1"}, {"10", "-0.001"},
            {"0", "1.23456789"}, {"0.000", "1.23"}, {"0E-10", "1.23"}, {"0E+10", "1.23"}, {"0E-10", "0E-3"},
            {"0E-5", "0.00"}, {"0E+5", "123456789"}, {"0E-1000000000", "1"}, {"0E-10", "1.23456789"},
            {"0E-3", "1E+5"}, {"0E-3", "1.0E+5"}};
        for (String[] pair : apart) {
            for (int precision : new int[] {1, 3, 5, 16}) {
                for (RoundingMode mode : RoundingMode.values()) {
                    MathContext context = new MathContext(precision, mode);
                    t(pair[0] + " " + pair[1] + " " + precision + " " + mode,
                            () -> d(pair[0]).add(d(pair[1]), context) + " " + d(pair[1]).add(d(pair[0]), context) + " "
                                    + d(pair[0]).subtract(d(pair[1]), context));
                }
            }
        }
        t("older modes", () -> BigDecimal.ROUND_UP + " " + BigDecimal.ROUND_DOWN + " " + BigDecimal.ROUND_CEILING + " "
                + BigDecimal.ROUND_FLOOR + " " + BigDecimal.ROUND_HALF_UP + " " + BigDecimal.ROUND_HALF_DOWN + " "
                + BigDecimal.ROUND_HALF_EVEN + " " + BigDecimal.ROUND_UNNECESSARY + " "
                + d("1.25").setScale(1, BigDecimal.ROUND_HALF_EVEN) + " " + d("1").divide(d("3"), 4) + " "
                + d("1").divide(d("3"), 2, BigDecimal.ROUND_UP));
        t("older mode 8", () -> d("1.25").setScale(1, 8));
        t("older mode -1", () -> d("1").divide(d("3"), 2, -1));
        t("setScale", () -> d("1.25").setScale(1));
        t("through Number", () -> {
            Number[] numbers = {d("1.99"), new BigInteger("4294967298"), 7, 2.5};
            StringBuilder text = new StringBuilder();
            for (Number number : numbers) {
                text.append(number.intValue()).append(' ').append(number.longValue()).append(' ')
                    .append(number.doubleValue()).append(' ').append(number.byteValue()).append(' ').append(number)
                    .append(number instanceof BigDecimal).append(number instanceof BigInteger)
                    .append(number instanceof Comparable).append(' ');
            }
            return text;
        });
    }

    static BigDecimal randomDecimal() {
        BigInteger unscaled = next(8) == 0 ? BigInteger.valueOf(next(3) - 1) : random();
        return new BigDecimal(unscaled, next(50) - 20);
    }

    static void seeded() {
        for (int i = 0; i < 250; i++) {
            BigDecimal a = randomDecimal();
            BigDecimal b = randomDecimal();
            MathContext context = new MathContext(1 + next(30), RoundingMode.values()[next(7)]);
            int scale = next(30) - 10;
            int power = next(40) - 15;
            String at = "seeded " + i;
            t(at, () -> a + " " + b + " " + context + " " + scale + " " + power);
            t(at + " text", () -> a.toEngineeringString() + " " + a.toPlainString() + " " + a.hashCode() + " "
                    + a.precision() + " " + a.stripTrailingZeros() + " " + a.doubleValue() + " " + a.floatValue() + " "
                    + a.longValue());
            t(at + " sums", () -> a.add(b) + " " + a.subtract(b) + " " + a.multiply(b) + " " + a.compareTo(b));
            t(at + " sums at", () -> a.add(b, context) + " " + a.subtract(b, context) + " " + a.multiply(b, context)
                    + " " + a.round(context));
            t(at + " divide", () -> a.divide(b));
            t(at + " divide at", () -> a.divide(b, context));
            t(at + " divide to", () -> a.divide(b, scale, context.getRoundingMode()) + " "
                    + a.setScale(scale, context.getRoundingMode()));
            t(at + " whole", () -> shown(a.divideAndRemainder(b)));
            t(at + " whole at", () -> shown(a.divideAndRemainder(b, context)));
            t(at + " sqrt", () -> a.abs().sqrt(context));
            t(at + " pow", () -> a.pow(power, context));
        }
    }

    public static void main(String[] args) {
        integers();
        made();
        edges();
        decimals();
        seeded();
    }

}
