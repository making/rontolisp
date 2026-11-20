import java.util.Random;

// Double.parseDouble and Float.parseFloat of decimal texts, seeded: leading and trailing
// zeros, points, exponents near each format's ends, as the bits they read as.
public class DecGrid {
    public static void main(String[] args) {
        Random r = new Random(7272);
        long hd = 0, hf = 0;
        int refused = 0;
        StringBuilder shown = new StringBuilder();
        for (int k = 0; k < 8000; k++) {
            StringBuilder t = new StringBuilder();
            if (r.nextInt(4) == 0) {
                t.append(r.nextBoolean() ? '-' : '+');
            }
            t.append("0".repeat(r.nextInt(3) == 0 ? r.nextInt(500) : r.nextInt(3)));
            int before = r.nextInt(20);
            for (int i = 0; i < before; i++) {
                t.append((char) ('0' + r.nextInt(10)));
            }
            if (r.nextInt(3) != 0) {
                t.append('.');
                t.append("0".repeat(r.nextInt(4) == 0 ? r.nextInt(450) : 0));
                int after = r.nextInt(20);
                for (int i = 0; i < after; i++) {
                    t.append((char) ('0' + r.nextInt(10)));
                }
            }
            if (r.nextInt(3) != 0) {
                t.append(r.nextBoolean() ? 'e' : 'E');
                int e = r.nextInt(3) == 0 ? r.nextInt(1400) - 700 : r.nextInt(80) - 40;
                t.append(e >= 0 && r.nextBoolean() ? "+" : "").append(e);
            }
            String s = t.toString();
            try {
                long d = Double.doubleToRawLongBits(Double.parseDouble(s));
                int f = Float.floatToRawIntBits(Float.parseFloat(s));
                hd = hd * 31 + d;
                hf = hf * 31 + f;
                if (k % 400 == 0) {
                    shown.append(s.length() > 60 ? s.substring(0, 30) + ".." + s.substring(s.length() - 20) : s).append(' ').append(Long.toHexString(d)).append(' ').append(Integer.toHexString(f)).append('\n');
                }
            } catch (NumberFormatException e) {
                refused++;
            }
        }
        System.out.print(shown);
        System.out.println(hd + " " + hf + " " + refused);
    }
}
