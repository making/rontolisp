public class CharNow {
    public static void main(String[] args) {
        int[] cps = {0, 9, 32, '$', '0', '9', 'A', 'Z', '_', 'a', 'z', 0x7f, 0xa0, 0xaa, 0xb5, 0xdf, 0xe9, 0x130, 0x1c5, 0x2b0, 0x300, 0x3a3, 0x3c2, 0x660, 0x5d0, 0x2028, 0x2029, 0x200b, 0x20ac, 0x2160, 0x3042, 0x4e2d, 0xe000, 0xfeff, 0xff21, 0xffff, 0xad, 0x203f, 0x00b2, 0x0085, 0x1f, 0x7, 0x1b, 0x1c4, 0x1c6, 0x24b6, 0x24d0, 0x2170, 0x10d0, 0x1e9e, 0xff10, 0x0969, 0x16ee, 0x3007};
        for (int cp : cps) {
            char c = (char) cp;
            System.out.println(Integer.toHexString(cp) + ": " + Character.isLetter(c) + " " + Character.isDigit(c) + " "
                    + Character.isLetterOrDigit(c) + " " + Character.isAlphabetic(c) + " " + Character.isWhitespace(c)
                    + " " + Character.isSpaceChar(c) + " " + Character.isUpperCase(c) + " " + Character.isLowerCase(c)
                    + " " + (int) Character.toUpperCase(c) + " " + (int) Character.toLowerCase(c) + " "
                    + Character.getNumericValue(c) + " " + Character.digit(c, 16));
        }
    }
}
