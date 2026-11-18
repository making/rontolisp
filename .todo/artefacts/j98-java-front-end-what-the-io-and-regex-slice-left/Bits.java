public class Bits {
    static final int[] INTS = {0, 1, -1, 2, 7, 8, 255, 256, 0x12345678, Integer.MAX_VALUE, Integer.MIN_VALUE, -2, 0x80000001, 1 << 30, -123456789};
    static final long[] LONGS = {0L, 1L, -1L, 2L, 255L, 0x123456789ABCDEFL, Long.MAX_VALUE, Long.MIN_VALUE, -2L, 1L << 62, 0x8000000000000001L, -1234567890123456789L, 0xFFFFFFFFL, 0x100000000L};

    public static void main(String[] args) {
        for (int i : INTS) {
            System.out.println(i + ": " + Integer.highestOneBit(i) + " " + Integer.lowestOneBit(i) + " "
                    + Integer.numberOfLeadingZeros(i) + " " + Integer.numberOfTrailingZeros(i) + " "
                    + Integer.reverse(i) + " " + Integer.reverseBytes(i) + " " + Integer.rotateLeft(i, 5) + " "
                    + Integer.rotateRight(i, 5) + " " + Integer.rotateLeft(i, -3) + " " + Integer.rotateLeft(i, 37)
                    + " " + Integer.rotateRight(i, 0) + " " + Integer.toUnsignedString(i, 16) + " "
                    + Integer.toUnsignedString(i, 7) + " " + Integer.toUnsignedString(i, 99));
            for (int j : new int[] {1, -1, 3, Integer.MIN_VALUE, 7, -7}) {
                System.out.println("  " + j + ": " + Integer.divideUnsigned(i, j) + " " + Integer.remainderUnsigned(i, j)
                        + " " + Integer.compareUnsigned(i, j) + " " + Integer.compare(i, j));
            }
        }
        try { Integer.divideUnsigned(1, 0); } catch (ArithmeticException e) { System.out.println(e.getMessage()); }
        try { Integer.remainderUnsigned(1, 0); } catch (ArithmeticException e) { System.out.println(e.getMessage()); }
        for (long i : LONGS) {
            System.out.println(i + ": " + Long.highestOneBit(i) + " " + Long.lowestOneBit(i) + " "
                    + Long.numberOfLeadingZeros(i) + " " + Long.numberOfTrailingZeros(i) + " " + Long.reverse(i)
                    + " " + Long.reverseBytes(i) + " " + Long.rotateLeft(i, 5) + " " + Long.rotateRight(i, 5) + " "
                    + Long.rotateLeft(i, -3) + " " + Long.rotateLeft(i, 69) + " " + Long.toUnsignedString(i) + " "
                    + Long.toUnsignedString(i, 16) + " " + Long.toUnsignedString(i, 36) + " " + Long.toOctalString(i)
                    + " " + Long.toUnsignedString(i, 1));
            for (long j : new long[] {1L, -1L, 3L, Long.MIN_VALUE, 10L, -7L}) {
                System.out.println("  " + j + ": " + Long.divideUnsigned(i, j) + " " + Long.remainderUnsigned(i, j)
                        + " " + Long.compareUnsigned(i, j));
            }
        }
        try { Long.divideUnsigned(1, 0); } catch (ArithmeticException e) { System.out.println(e.getMessage()); }
        System.out.println(Integer.SIZE + " " + Integer.BYTES + " " + Long.SIZE + " " + Long.BYTES + " " + Short.SIZE
                + " " + Short.BYTES + " " + Byte.SIZE + " " + Byte.BYTES + " " + Character.SIZE + " " + Character.BYTES
                + " " + Double.SIZE + " " + Double.BYTES + " " + Float.SIZE + " " + Float.BYTES);
        for (String s : new String[] {"0", "10", "-10", "+10", "0x1F", "0X1f", "#ff", "-0x80000000", "0x7fffffff",
                "010", "-010", "0", "-0", "00", "08", "0x", "", "-", "+-1", "0x-1", "0x+1", "2147483648", "-2147483648",
                "0x80000000", "abc", " 1", "1 ", "#", "-#10", "+0x10", "0777", "1_000", "0xG" }) {
            try {
                System.out.println("decode " + s + " = " + Integer.decode(s));
            } catch (NumberFormatException e) {
                System.out.println("decode " + s + " ! " + e.getMessage());
            }
            try {
                System.out.println("ldecode " + s + " = " + Long.decode(s));
            } catch (NumberFormatException e) {
                System.out.println("ldecode " + s + " ! " + e.getMessage());
            }
        }
        for (String s : new String[] {"0x8000000000000000", "-0x8000000000000000", "0x7fffffffffffffff", "9223372036854775808"}) {
            try {
                System.out.println("ldecode " + s + " = " + Long.decode(s));
            } catch (NumberFormatException e) {
                System.out.println("ldecode " + s + " ! " + e.getMessage());
            }
        }
        try { Integer.decode(null); } catch (NullPointerException e) { System.out.println("npe"); }
        for (String s : new String[] {"0", "18446744073709551615", "18446744073709551616", "-1", "+5", "", "abc", "9223372036854775808", "99999999999999999999", "+", "1 "}) {
            try {
                System.out.println("pul " + s + " = " + Long.parseUnsignedLong(s));
            } catch (NumberFormatException e) {
                System.out.println("pul " + s + " ! " + e.getMessage());
            }
        }
        for (String s : new String[] {"ffffffffffffffff", "10000000000000000", "-1", "zz", "7fffffffffffffff", ""}) {
            try {
                System.out.println("pul16 " + s + " = " + Long.parseUnsignedLong(s, 16));
            } catch (NumberFormatException e) {
                System.out.println("pul16 " + s + " ! " + e.getMessage());
            }
        }
        try { Long.parseUnsignedLong(null); } catch (NumberFormatException e) { System.out.println(e.getMessage()); }
        try { Long.parseUnsignedLong("1", 99); } catch (NumberFormatException e) { System.out.println(e.getMessage()); }
        try { Long.parseUnsignedLong("3w5e11264sgsg", 36); System.out.println(Long.parseUnsignedLong("3w5e11264sgsf", 36)); } catch (NumberFormatException e) { System.out.println(e.getMessage()); }
        System.out.println(Long.hashCode(5L) + " " + Long.signum(-5L) + " " + Integer.signum(9));
    }
}
