public class HexPadding {
    public static void main(String[] args) {
        for (String h : new String[]{ "0x1.ff8p0", "0x1.0000000000008p0", "0x1.08p0", "0x1.1p0", "0x1.10p0", "0x1.101p0", "0x1.f8p0", "0x0.88p-1022", "0x0.8p-1022", "0x1.8p3" }) {
            double d = Double.parseDouble(h);
            for (String f : new String[]{ "%020.1a", "%020.2a", "%020.3a", "%020.12a", "%030.14a" }) {
                String r = String.format(f, d);
                System.out.println(h + " " + Double.toHexString(d) + " " + f + " [" + r + "] " + r.length());
            }
        }
        float x = Float.parseFloat("0x1.8p0");
        for (String f : new String[]{ "%020.3a", "%020a", "%020.13a" }) { String r = String.format(f, x); System.out.println("float " + f + " [" + r + "] " + r.length()); }
    }
}
