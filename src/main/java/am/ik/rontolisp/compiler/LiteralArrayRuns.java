package am.ik.rontolisp.compiler;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;

/**
 * The runs of equal immediates in a general array literal, which both compile backends
 * build with one fill instead of one store per element: a bit table is mostly runs, and a
 * store per element put cxml's 55,296-bit name tables past the JVM's method limit.
 */
public final class LiteralArrayRuns {

	/**
	 * The shortest run built as a fill. Below it the stores cost about what the fill
	 * does, and an ordinary small literal keeps its stores.
	 */
	static final int MIN_RUN = 8;

	private LiteralArrayRuns() {
	}

	/**
	 * How many elements the build starting at an index covers.
	 * @param data the row-major elements, a null one standing for nil
	 * @param start the index the build starts at
	 * @return the length of the run of equal immediates at {@code start} when it is worth
	 * a fill, else 1
	 */
	public static int length(LispVal[] data, int start) {
		LispVal value = element(data, start);
		if (!(value instanceof LispInteger || value instanceof LispChar || value instanceof LispNil)) {
			return 1;
		}
		int end = start + 1;
		while (end < data.length && value.equals(element(data, end))) {
			end++;
		}
		return (end - start >= MIN_RUN) ? end - start : 1;
	}

	/**
	 * An element of a literal's data.
	 * @param data the row-major elements
	 * @param index the index
	 * @return the element, nil for a null one
	 */
	public static LispVal element(LispVal[] data, int index) {
		LispVal value = data[index];
		return (value == null) ? LispNil.INSTANCE : value;
	}

}
