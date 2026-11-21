public class Seams {
    public static void main(String[] args) {
        String hi = String.valueOf((char) 0xD83D), lo = String.valueOf((char) 0xDE00), hi2 = String.valueOf((char) 0xDBFF), lo2 = String.valueOf((char) 0xDFFF);
        int n = 7;
        String a = hi + lo + "x" + hi + lo + n + hi + lo;
        String b = "ab" + hi + lo + hi2 + lo2 + hi + n + lo;
        String c = hi + (lo + hi) + lo;
        String d = lo + hi;
        String e = hi + hi + lo + lo;
        System.out.println(a + " " + a.codePointCount(0, a.length()) + " " + a.hashCode());
        System.out.println(b + " " + b.codePointCount(0, b.length()) + " " + b.hashCode() + " " + Integer.toHexString(b.codePointAt(3)));
        System.out.println(c + " " + c.codePointCount(0, c.length()) + " " + c.hashCode());
        System.out.println(d.codePointCount(0, d.length()) + " " + d.hashCode() + " " + e.codePointCount(0, e.length()) + " " + e.hashCode());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(hi).append(lo.charAt(0));
        }
        System.out.println(sb + " " + sb.toString().hashCode());
    }
}
