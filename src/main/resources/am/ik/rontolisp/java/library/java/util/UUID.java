// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.io.Serializable;

// A 128-bit value held as two longs. How fromString reads a text, beyond the canonical
// form the specification gives, was learned from a JDK's answers: the text is split at
// its dashes into five parts, each read as a signed hexadecimal long and cut to its field.
public final class UUID implements Serializable, Comparable<UUID> {

    private static final String DIGITS = "0123456789abcdef";

    private static Random source;

    private final long mostSigBits;

    private final long leastSigBits;

    public UUID(long mostSigBits, long leastSigBits) {
        this.mostSigBits = mostSigBits;
        this.leastSigBits = leastSigBits;
    }

    /** Version 4, variant 2: 122 random bits. */
    public static UUID randomUUID() {
        if (source == null) {
            source = new Random();
        }
        long most = (source.nextLong() & ~0xF000L) | 0x4000L;
        long least = (source.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(most, least);
    }

    public static UUID fromString(String name) {
        if (name.length() > 36) {
            throw new IllegalArgumentException("UUID string too large");
        }
        int[] dashes = new int[5];
        int found = 0;
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) == '-') {
                if (found == 4) {
                    throw invalid(name);
                }
                dashes[found] = i;
                found++;
            }
        }
        if (found != 4) {
            throw invalid(name);
        }
        dashes[4] = name.length();
        long first = parsePart(name, 0, dashes[0]);
        long second = parsePart(name, dashes[0] + 1, dashes[1]);
        long third = parsePart(name, dashes[1] + 1, dashes[2]);
        long fourth = parsePart(name, dashes[2] + 1, dashes[3]);
        long fifth = parsePart(name, dashes[3] + 1, dashes[4]);
        return new UUID(((first & 0xFFFFFFFFL) << 32) | ((second & 0xFFFFL) << 16) | (third & 0xFFFFL),
                ((fourth & 0xFFFFL) << 48) | (fifth & 0xFFFFFFFFFFFFL));
    }

    private static IllegalArgumentException invalid(String name) {
        return new IllegalArgumentException("Invalid UUID string: " + name);
    }

    /** The part from begin to end as a signed hexadecimal long, refused as a long is. */
    private static long parsePart(String name, int begin, int end) {
        if (begin == end) {
            throw new NumberFormatException("For input string: \"\" under radix 16");
        }
        int at = begin;
        if (name.charAt(at) == '+') {
            at++;
            if (at == end) {
                throw refused(name, begin, end, 1);
            }
        }
        long value = 0;
        for (; at < end; at++) {
            int digit = Character.digit(name.charAt(at), 16);
            if (digit < 0 || value > (Long.MAX_VALUE - digit) / 16) {
                throw refused(name, begin, end, at - begin);
            }
            value = value * 16 + digit;
        }
        return value;
    }

    private static NumberFormatException refused(String name, int begin, int end, int index) {
        return new NumberFormatException("Error at index " + index + " in: \"" + name.substring(begin, end) + "\"");
    }

    public long getLeastSignificantBits() {
        return leastSigBits;
    }

    public long getMostSignificantBits() {
        return mostSigBits;
    }

    public int version() {
        return (int) ((mostSigBits >> 12) & 0xF);
    }

    public int variant() {
        if (leastSigBits >= 0) {
            return 0;
        }
        long top = leastSigBits >>> 61;
        return top == 7 ? 7 : top == 6 ? 6 : 2;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(36);
        hex(text, mostSigBits >>> 32, 8);
        text.append('-');
        hex(text, mostSigBits >>> 16, 4);
        text.append('-');
        hex(text, mostSigBits, 4);
        text.append('-');
        hex(text, leastSigBits >>> 48, 4);
        text.append('-');
        hex(text, leastSigBits, 12);
        return text.toString();
    }

    /** The low digits of value, most significant first. */
    private static void hex(StringBuilder text, long value, int digits) {
        for (int shift = (digits - 1) * 4; shift >= 0; shift -= 4) {
            text.append(DIGITS.charAt((int) ((value >>> shift) & 0xF)));
        }
    }

    @Override
    public int hashCode() {
        long both = mostSigBits ^ leastSigBits;
        return ((int) (both >> 32)) ^ (int) both;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof UUID other && mostSigBits == other.mostSigBits && leastSigBits == other.leastSigBits;
    }

    @Override
    public int compareTo(UUID val) {
        return mostSigBits < val.mostSigBits ? -1
                : mostSigBits > val.mostSigBits ? 1 : leastSigBits < val.leastSigBits ? -1 : leastSigBits > val.leastSigBits ? 1 : 0;
    }
}
