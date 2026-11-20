import java.util.Random;

// Double.parseDouble and Float.parseFloat of hexadecimal floats: seeded texts around the
// ends of each format and its ties, written as the bits they read as.
public class HexGrid {
    public static void main(String[] args) {
        Random r = new Random(72);
        String digits = "0123456789abcdefABCDEF";
        long hd = 0, hf = 0;
        int refused = 0;
        StringBuilder shown = new StringBuilder();
        for (int k = 0; k < 6000; k++) {
            StringBuilder t = new StringBuilder();
            if (r.nextInt(4) == 0) {
                t.append(r.nextBoolean() ? '-' : '+');
            }
            t.append(r.nextBoolean() ? "0x" : "0X");
            int before = r.nextInt(18);
            for (int i = 0; i < before; i++) {
                t.append(digits.charAt(r.nextInt(digits.length())));
            }
            if (r.nextInt(5) != 0) {
                t.append('.');
                int after = r.nextInt(18);
                for (int i = 0; i < after; i++) {
                    t.append(digits.charAt(r.nextInt(digits.length())));
                }
            }
            if (r.nextInt(10) != 0) {
                t.append(r.nextBoolean() ? 'p' : 'P');
                int e = r.nextInt(3) == 0 ? r.nextInt(2400) - 1200 : r.nextInt(300) - 150;
                if (e >= 0 && r.nextBoolean()) {
                    t.append('+');
                }
                t.append(e);
            }
            if (r.nextInt(8) == 0) {
                t.append("fFdD".charAt(r.nextInt(4)));
            }
            String s = t.toString();
            try {
                long d = Double.doubleToRawLongBits(Double.parseDouble(s));
                int f = Float.floatToRawIntBits(Float.parseFloat(s));
                hd = hd * 31 + d;
                hf = hf * 31 + f;
                if (k % 300 == 0) {
                    shown.append(s).append(' ').append(Long.toHexString(d)).append(' ').append(Integer.toHexString(f)).append('\n');
                }
            } catch (NumberFormatException e) {
                refused++;
            }
        }
        String[] edges = {"0x1p-1074", "0x1p-1075", "0x1.0000000000001p-1075", "0x1p-1076", "0x1.fffffffffffffp1023", "0x1.fffffffffffff8p1023", "0x1.fffffffffffff7ffp1023", "0x1p1024", "0x0.0000000000001p-1022", "0x1p-149", "0x1p-150", "0x1.000001p-150", "0x1.fffffep127", "0x1.ffffffp127", "0x1.fffffefffp127", "0x1.00000000000008p0", "0x1.00000000000018p0", "0x1.000002p0", "0x1.000003p0", "0x.8p1", "0x8.p-3", "0x0p0", "-0x0p0", "0x1P+10", " 0x1p1 ", "0x1p1d", "0xp1", "0x1", "0x1p", "0x1.8p1.5", "0x1p99999999999", "0x1p-99999999999", "0x10000000000000000000000000000000000000000p-200"};
        for (String e : edges) {
            try {
                shown.append(e).append(' ').append(Long.toHexString(Double.doubleToRawLongBits(Double.parseDouble(e)))).append(' ').append(Integer.toHexString(Float.floatToRawIntBits(Float.parseFloat(e)))).append('\n');
            } catch (NumberFormatException x) {
                shown.append(e).append(' ').append(x.getMessage()).append('\n');
            }
        }
        System.out.print(shown);
        System.out.println(hd + " " + hf + " " + refused);
    }
}
