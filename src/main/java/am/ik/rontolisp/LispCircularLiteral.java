package am.ik.rontolisp;

/**
 * A literal of program source that a {@code #n=} reader label closed into a circle (CLHS
 * 2.4.8.3), carried through the front end as one atom.
 *
 * <p>
 * Many passes over a program walk a form as a tree, through quoted data as well, so a
 * circle in a form would overflow or hang each of them. {@link LispTrees#shieldCircles}
 * wraps such a datum where the source is read, every pass sees an atom, and evaluating
 * the atom answers the datum: {@code quote} and the code position alike, on every
 * backend. Only what must see inside it does: {@code PackageResolver} (through
 * {@link LispTrees#rewriteDatumSymbols}), {@link StructLiteralFolder}, the compilers of
 * {@code quote} and a macro, whose arguments are unwrapped and whose expansion is wrapped
 * again.
 *
 * <p>
 * A datum that only SHARES nodes has no circle and is not wrapped: a tree walk ends on
 * it.
 */
public final class LispCircularLiteral implements LispVal {

	/**
	 * Whether any wrapper was ever made: a program with no circle in its source, nearly
	 * every program, walks no form looking for one.
	 */
	private static volatile boolean anyMade;

	private final LispVal datum;

	/**
	 * Wraps a datum.
	 * @param datum the datum, which holds a circle
	 */
	public LispCircularLiteral(LispVal datum) {
		this.datum = datum;
		anyMade = true;
	}

	/**
	 * {@return whether any wrapper was ever made in this process}
	 */
	static boolean anyMade() {
		return anyMade;
	}

	/**
	 * {@return the datum}
	 */
	public LispVal datum() {
		return this.datum;
	}

	/**
	 * The value a quoted datum denotes.
	 * @param quoted what a {@code quote} form holds
	 * @return the datum when it is wrapped, else {@code quoted}
	 */
	public static LispVal unwrapped(LispVal quoted) {
		return (quoted instanceof LispCircularLiteral literal) ? literal.datum : quoted;
	}

	@Override
	public String print() {
		return "#<circular literal>";
	}

	@Override
	public String display() {
		return print();
	}

}
