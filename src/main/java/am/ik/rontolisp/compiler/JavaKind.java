package am.ik.rontolisp.compiler;

/**
 * The smallest token of a value that every {@code java:} conversion cost is a pure
 * function of ({@link JavaOverloads#kindCost}): one of the {@link Lisp} kinds, or -- for
 * a wrapped host object -- its exact class, a {@link JavaType}. Two values of one kind
 * convert at the same cost to every parameter type, so an overload chosen for a list of
 * kinds is the overload chosen for every argument list of those kinds; that is what lets
 * a call site be resolved once, at run time per kind list or at compile time from the
 * kinds its arguments are statically known to have ({@link JavaSiteResolver}).
 * <p>
 * A cons or a Lisp array has no kind (its cost sums its elements), and neither has a
 * value the bridge never marshals (a symbol, a bignum, a hash table).
 */
public interface JavaKind {

	/** The kinds of the Lisp values the bridge marshals. */
	enum Lisp implements JavaKind {

		/** {@code nil}: {@code boolean} false, or {@code null} for any reference. */
		NIL,

		/** {@code t}: {@code boolean} true. */
		T,

		/** An integer that fits a {@code long}. */
		INTEGER,

		/** A float. */
		FLOAT,

		/** A string of UTF-16 length 1: it may also narrow to a {@code char}. */
		STRING_1,

		/** Any other string. */
		STRING,

		/** A character in the Basic Multilingual Plane: it fits a Java {@code char}. */
		CHAR,

		/** A supplementary character: it fits an {@code int}, never a {@code char}. */
		SUPPLEMENTARY_CHAR,

		/** A function value: it becomes a proxy where an interface is expected. */
		FUNCTION

	}

}
