package am.ik.rontolisp;

/**
 * Which packed float width a value has: the one designator every backend reads, replacing
 * the booleans the widths used to ride as. A boolean is a two-valued type, so anything
 * carrying a width as one admits exactly two widths and answers a third by falling into
 * whichever arm was written second -- silently. That is the defect {@code .kb/vec.md}'s
 * "Asking a packed array its width" records, and an enum is what makes it a compile error
 * instead: every reader is an exhaustive {@code switch}, so a fourth width has to be
 * ANSWERED at each of them rather than inherited.
 *
 * <p>
 * Across a backend boundary the width travels as {@link #code()}, a small integer, and is
 * converted back with {@link #ofCode(int)} once at each entry point. That keeps the
 * property the {@code %la-gather-strided} protocol was designed for -- a kernel reads a
 * width without comparing symbols -- while admitting more than two values. Convert at the
 * boundary and switch over the enum after it; never branch on the raw code, or the
 * exhaustiveness is lost again at the site that matters.
 *
 * <p>
 * This type deliberately references NOTHING else in the project, not even
 * {@code LispNames}. It sits in the root package so that {@code reader},
 * {@code codegen.wasm}, {@code codegen.jvm} and {@code eval} can all reach it, and a
 * method reaching back into the value model would fold it into the root package's one
 * designed class cycle ({@code LispVal} and its permits, {@code PackageCycleTest}) --
 * which that test allows but exists to keep small. The mapping between a width and its
 * Lisp element-type name lives on the other side, with the arrays.
 */
public enum FloatWidth {

	/** {@code single-float}: an f32, the {@code #f(...)} literal. */
	SINGLE(0),

	/** {@code double-float}: an f64, the {@code #d(...)} literal. */
	DOUBLE(1),

	/**
	 * {@code bfloat16}: the top sixteen bits of an f32, the {@code #bf16(...)} literal.
	 */
	BFLOAT16(2);

	private final int code;

	FloatWidth(int code) {
		this.code = code;
	}

	/**
	 * The small integer this width travels as across a backend boundary. Stable: a
	 * compiled program and the runtime that reads it are built from the same source, but
	 * the codes reach emitted output, so reordering them changes bytes.
	 * @return the code
	 */
	public int code() {
		return this.code;
	}

	/**
	 * The width a code names.
	 * @param code the code, as {@link #code()} produced it
	 * @return the width
	 * @throws IllegalArgumentException if the code names no width
	 */
	public static FloatWidth ofCode(int code) {
		for (FloatWidth width : values()) {
			if (width.code == code) {
				return width;
			}
		}
		throw new IllegalArgumentException("no packed float width has code " + code);
	}

}
