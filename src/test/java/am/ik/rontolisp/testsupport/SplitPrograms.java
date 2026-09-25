package am.ik.rontolisp.testsupport;

/**
 * A program whose JVM class needs more constant-pool entries than one class file can
 * index, so the backend writes it as its class plus {@code $PartN} classes
 * ({@code .kb/jvm-method-size-limits.md}): 700 functions of 50 distinct strings each,
 * 35,000 strings at two pool entries apiece. Every function is called, so the tree-shaker
 * keeps them all and the split is real rather than an artifact of an unshaken pool.
 */
public final class SplitPrograms {

	private static final int FUNCTIONS = 700;

	private static final int STRINGS_EACH = 50;

	private SplitPrograms() {
	}

	/**
	 * {@return the program} It prints the total length of the one string it picks from
	 * each function.
	 */
	public static String pastOneClass() {
		StringBuilder source = new StringBuilder();
		for (int k = 0; k < FUNCTIONS; k++) {
			source.append("(defun f").append(k).append(" (i) (case i");
			for (int i = 0; i < STRINGS_EACH; i++) {
				source.append(" (").append(i).append(" \"s").append(k).append('-').append(i).append("\")");
			}
			source.append("))\n");
		}
		source.append("(defvar *total* 0)\n(progn");
		for (int k = 0; k < FUNCTIONS; k++) {
			source.append(" (incf *total* (length (f")
				.append(k)
				.append(" (mod ")
				.append(k)
				.append(' ')
				.append(STRINGS_EACH)
				.append("))))");
		}
		return source.append(")\n(print *total*)\n").toString();
	}

	/**
	 * The output of {@link #pastOneClass()}.
	 * @return what it prints
	 */
	public static String pastOneClassOutput() {
		int total = 0;
		for (int k = 0; k < FUNCTIONS; k++) {
			total += ("s" + k + "-" + (k % STRINGS_EACH)).length();
		}
		return String.valueOf(total);
	}

}
