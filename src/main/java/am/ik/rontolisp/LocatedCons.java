package am.ik.rontolisp;

/**
 * A cons the reader produced from a NAMED file while reading code that will run in this
 * process -- the interpreter's entry file, a {@code load}, an ASDF component -- carrying
 * the file and the line its datum starts on, so an uncaught runtime condition can say
 * where it happened ({@code eval.ConditionTrace}).
 *
 * <p>
 * <b>A subclass, not a field on every cons and not a side table.</b> A third field on
 * {@link LispCons} is free on HotSpot's default layout (24 bytes with two reference
 * fields or three) but grows every cons from 16 to 24 bytes in the native image the CLI
 * ships as and under compact object headers -- data conses included, to locate the few
 * that are code (measured 2026-09-26, GraalVM 25.0.3). A weak identity table keyed by
 * cons costs about twice this subclass per entry, pins nothing only through reference
 * processing on every GC, and turns "is this form located?" into a synchronized probe.
 * The subclass answers that with a type test, which is what lets the evaluator track the
 * innermost located form on EVERY step of its loop for free and consult nothing until a
 * condition escapes.
 *
 * <p>
 * Only the outermost cons of each datum is a {@code LocatedCons} (the cells that chain a
 * list's elements are not), and a pass that rebuilds one through {@link LispCons#rebuilt}
 * keeps it located. Its car and cdr behave exactly as a {@link LispCons}'s: nothing
 * observable to a program -- {@code eq}, {@code equal}, printing, mutation -- changes.
 * The compile path records its positions in {@link SourceProvenance} instead and never
 * produces one of these.
 */
public final class LocatedCons extends LispCons {

	private final String file;

	private final int line;

	/**
	 * Create a located cons cell.
	 * @param car the first element
	 * @param cdr the rest of the list
	 * @param file the origin file
	 * @param line the 1-based line the datum starts on
	 */
	public LocatedCons(LispVal car, LispVal cdr, String file, int line) {
		super(car, cdr);
		this.file = file;
		this.line = line;
	}

	/**
	 * The file this datum was read from.
	 * @return the origin file, as the reader was given it
	 */
	public String file() {
		return this.file;
	}

	/**
	 * The line this datum starts on.
	 * @return the 1-based line
	 */
	public int line() {
		return this.line;
	}

}
