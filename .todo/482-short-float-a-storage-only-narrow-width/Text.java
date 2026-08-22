/** Does a shortest-decimal search round-trip every f16 bit pattern? And how many digits does it need? */
public class Text {
	static String halfText(short bits) {
		float v = Float.float16ToFloat(bits);
		if (Float.isNaN(v) || Float.isInfinite(v)) return Float.toString(v);
		for (int p = 1; p <= 6; p++) {
			String s = new java.math.BigDecimal(v).round(new java.math.MathContext(p)).toString();
			if (Float.floatToFloat16(Float.parseFloat(s)) == bits) return s;
		}
		return Float.toString(v);
	}

	public static void main(String[] a) {
		int[] hist = new int[8];
		int fail = 0;
		String worst = "";
		for (int i = 0; i < 65536; i++) {
			short b = (short) i;
			float v = Float.float16ToFloat(b);
			if (Float.isNaN(v) || Float.isInfinite(v)) continue;
			String s = halfText(b);
			if (Float.floatToFloat16(Float.parseFloat(s)) != b) { fail++; continue; }
			int digits = s.replaceAll("[-.]", "").replaceAll("^0+", "").replaceAll("E.*", "").replaceAll("0+$", "").length();
			hist[Math.min(7, digits)]++;
			if (digits >= 5 && worst.isEmpty()) worst = String.format("h=%04x -> %s (f32 spelling %s)", i, s, v);
		}
		System.out.println("round-trip failures: " + fail);
		System.out.print("significant-digit histogram: ");
		for (int d = 0; d < 8; d++) if (hist[d] > 0) System.out.print(d + ":" + hist[d] + "  ");
		System.out.println("\nexample needing 5+: " + worst);
		for (short b : new short[] { Float.floatToFloat16(0.1f), Float.floatToFloat16(1.5f), Float.floatToFloat16(3.14159f),
				Float.floatToFloat16(65504f), Float.floatToFloat16(6.1e-5f), Float.floatToFloat16(6e-8f) })
			System.out.printf("  bits %04x  f32 spelling %-14s  f16 shortest %s%n", b & 0xffff, Float.float16ToFloat(b), halfText(b));
	}
}
