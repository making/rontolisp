import java.util.Arrays;

public class Chars {
    interface Op { Object run(); }

    static void t(String name, Op op) {
        try {
            System.out.println(name + " = " + op.run());
        } catch (RuntimeException e) {
            System.out.println(name + " ! " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        int[] cps = {0, 9, 32, '$', '0', '9', 'A', 'Z', '_', 'a', 'z', 0x7f, 0xa0, 0xaa, 0xb5, 0xdf, 0xe9, 0x130, 0x1c5, 0x2b0, 0x300, 0x3a3, 0x3c2, 0x660, 0x5d0, 0x2028, 0x2029, 0x200b, 0x20ac, 0x2160, 0x3042, 0x4e2d, 0xd800, 0xdbff, 0xdc00, 0xdfff, 0xe000, 0xfeff, 0xff21, 0xffff, 0x10000, 0x1d11e, 0x1f600, 0x10ffff, 0xad, 0x203f, 0x00b2, 0x0085, 0x1f, 0x7, 0x1b};
        for (int cp : cps) {
            StringBuilder line = new StringBuilder(Integer.toHexString(cp)).append(':');
            line.append(' ').append(Character.getType(cp));
            line.append(' ').append(Character.isLetter(cp)).append(' ').append(Character.isDigit(cp)).append(' ')
                .append(Character.isLetterOrDigit(cp)).append(' ').append(Character.isAlphabetic(cp)).append(' ')
                .append(Character.isWhitespace(cp)).append(' ').append(Character.isSpaceChar(cp)).append(' ')
                .append(Character.isUpperCase(cp)).append(' ').append(Character.isLowerCase(cp)).append(' ')
                .append(Character.isJavaIdentifierStart(cp)).append(' ').append(Character.isJavaIdentifierPart(cp))
                .append(' ').append(Character.isSupplementaryCodePoint(cp)).append(' ')
                .append(Character.isBmpCodePoint(cp)).append(' ').append(Character.isValidCodePoint(cp)).append(' ')
                .append(Character.charCount(cp)).append(' ').append(Character.toUpperCase(cp)).append(' ')
                .append(Character.toLowerCase(cp)).append(' ').append(Character.isISOControl(cp)).append(' ')
                .append(Character.isDefined(cp)).append(' ').append(Character.isTitleCase(cp)).append(' ')
                .append(Character.getNumericValue(cp)).append(' ').append(Character.digit(cp, 16)).append(' ')
                .append(Character.isIdentifierIgnorable(cp)).append(' ').append(Character.isUnicodeIdentifierStart(cp))
                .append(' ').append(Character.isUnicodeIdentifierPart(cp)).append(' ').append(Arrays.toString(Character.toChars(cp)).length());
            if (cp < 0x10000) {
                char c = (char) cp;
                line.append(" |").append(Character.getType(c)).append(' ').append(Character.isJavaIdentifierStart(c))
                    .append(' ').append(Character.isJavaIdentifierPart(c)).append(' ')
                    .append(Character.isHighSurrogate(c)).append(' ').append(Character.isLowSurrogate(c)).append(' ')
                    .append(Character.isSurrogate(c)).append(' ').append(Character.isLetter(c)).append(' ').append(Character.isISOControl(c)).append(' ').append(Character.isDefined(c)).append(' ').append(Character.isTitleCase(c));
            }
            System.out.println(line);
        }
        System.out.println(Character.MIN_RADIX + " " + Character.MAX_RADIX + " " + Character.MIN_CODE_POINT + " "
                + Character.MAX_CODE_POINT + " " + Character.MIN_SUPPLEMENTARY_CODE_POINT + " "
                + (int) Character.MIN_HIGH_SURROGATE + " " + (int) Character.MAX_HIGH_SURROGATE + " "
                + (int) Character.MIN_LOW_SURROGATE + " " + (int) Character.MAX_LOW_SURROGATE + " "
                + (int) Character.MIN_SURROGATE + " " + (int) Character.MAX_SURROGATE);
        System.out.println(Character.UPPERCASE_LETTER + " " + Character.LOWERCASE_LETTER + " " + Character.TITLECASE_LETTER + " " + Character.MODIFIER_LETTER + " " + Character.OTHER_LETTER + " " + Character.NON_SPACING_MARK + " " + Character.ENCLOSING_MARK + " " + Character.COMBINING_SPACING_MARK + " " + Character.DECIMAL_DIGIT_NUMBER + " " + Character.LETTER_NUMBER + " " + Character.OTHER_NUMBER + " " + Character.SPACE_SEPARATOR + " " + Character.LINE_SEPARATOR + " " + Character.PARAGRAPH_SEPARATOR + " " + Character.CONTROL + " " + Character.FORMAT + " " + Character.PRIVATE_USE + " " + Character.SURROGATE + " " + Character.DASH_PUNCTUATION + " " + Character.START_PUNCTUATION + " " + Character.END_PUNCTUATION + " " + Character.CONNECTOR_PUNCTUATION + " " + Character.OTHER_PUNCTUATION + " " + Character.MATH_SYMBOL + " " + Character.CURRENCY_SYMBOL + " " + Character.MODIFIER_SYMBOL + " " + Character.OTHER_SYMBOL + " " + Character.INITIAL_QUOTE_PUNCTUATION + " " + Character.FINAL_QUOTE_PUNCTUATION + " " + Character.UNASSIGNED);
        char[] pair = Character.toChars(0x1f600);
        System.out.println((int) pair[0] + " " + (int) pair[1] + " " + Character.toCodePoint(pair[0], pair[1]) + " "
                + Character.isSurrogatePair(pair[0], pair[1]) + " " + Character.isSurrogatePair(pair[1], pair[0]) + " " + (int) Character.highSurrogate(0x1f600) + " " + (int) Character.lowSurrogate(0x1f600));
        t("toChars -1", () -> Character.toChars(-1));
        t("toChars 0x110000", () -> Character.toChars(0x110000));
        t("toString cp", () -> Character.toString(0x1f600) + Character.toString(97));
        t("toString -1", () -> Character.toString(-1));
        t("valueOf", () -> Character.valueOf('x').equals('x') + " " + Character.valueOf('x').hashCode() + " " + Character.hashCode('y'));
    }
}
