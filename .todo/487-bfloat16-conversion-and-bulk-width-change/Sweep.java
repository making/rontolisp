import am.ik.rontolisp.BFloat16;

/**
 * Exhaustive check of the f32 -> bfloat16 narrowing over ALL 2^32 float32 inputs, against
 * an independently written textbook round-to-nearest-even oracle (compare the dropped
 * sixteen bits against half an ulp, break the tie towards the even survivor) rather than
 * against the shipped add-and-shift form. Single threaded on purpose.
 *
 * NaN inputs are excluded from the value comparison and checked separately: the shipped
 * narrowing carries a NaN's payload across from the f64 by hand rather than through the
 * f32, so the oracle is not the authority there -- what is asserted instead is the
 * contract, that a NaN stays a NaN of the same sign and never becomes an infinity.
 *
 * javac -cp target/classes -d /tmp/sweep Sweep.java && java -cp target/classes:/tmp/sweep Sweep
 */
public class Sweep {

	/** The textbook narrowing: truncate, then round the dropped bits to nearest even. */
	static int oracle(int f32Bits) {
		int survivor = (f32Bits >>> 16) & 0xffff;
		int dropped = f32Bits & 0xffff;
		if (dropped > 0x8000 || (dropped == 0x8000 && (survivor & 1) == 1)) {
			survivor = (survivor + 1) & 0xffff;
		}
		return survivor;
	}

	public static void main(String[] args) {
		long mismatches = 0;
		long nanMismatches = 0;
		long checked = 0;
		String first = null;
		for (long i = 0; i <= 0xffffffffL; i++) {
			int bits = (int) i;
			float value = Float.intBitsToFloat(bits);
			int got = BFloat16.bits(value);
			if (Float.isNaN(value)) {
				boolean stillNan = (got & 0x7f80) == 0x7f80 && (got & 0x7f) != 0;
				boolean sameSign = ((got >>> 15) & 1) == (bits >>> 31);
				if (!stillNan || !sameSign) {
					nanMismatches++;
				}
				continue;
			}
			checked++;
			int want = oracle(bits);
			if (got != want) {
				mismatches++;
				if (first == null) {
					first = String.format("f32 %08x -> got %04x want %04x", bits, got, want);
				}
			}
		}
		System.out.println("non-NaN inputs checked: " + checked);
		System.out.println("mismatches: " + mismatches + (first == null ? "" : "  first " + first));
		System.out.println("NaN contract violations: " + nanMismatches);
	}

}
