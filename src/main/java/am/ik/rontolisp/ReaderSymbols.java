package am.ik.rontolisp;

/**
 * The {@code cl} symbols the reader writes on its own account: a backquote template's
 * list-building calls, the {@code quote} of {@code 'x}, the {@code function} of
 * {@code #'x}. The source spells none of them, so no package's view of those names
 * applies -- CL's reader builds them from the symbols themselves, and {@code `(a . b)}
 * under a package shadowing {@code cons} still calls {@code cl:cons}. A pass ahead of the
 * resolver that writes one the source does not spell takes it from here for the same
 * reason ({@link #FIND_CLASS}).
 * <p>
 * Forms keep their bare spelling ({@code LIST}) through every pass ahead of the resolver,
 * which matches on it; what tells one of these from a token of the same name is identity,
 * and {@link PackageResolver} leaves these instances alone. A copy of the symbol is a
 * token again, resolved in the current package.
 */
public final class ReaderSymbols {

	public static final LispSymbol QUOTE = new LispSymbol(LispNames.QUOTE);

	public static final LispSymbol FUNCTION = new LispSymbol(LispNames.FUNCTION);

	public static final LispSymbol LAMBDA = new LispSymbol(LispNames.LAMBDA);

	public static final LispSymbol LIST = new LispSymbol(LispNames.LIST);

	public static final LispSymbol LIST_STAR = new LispSymbol(LispNames.LIST_STAR);

	public static final LispSymbol CONS = new LispSymbol(LispNames.CONS);

	public static final LispSymbol APPEND = new LispSymbol(LispNames.APPEND);

	public static final LispSymbol NCONC = new LispSymbol(LispNames.NCONC);

	public static final LispSymbol APPLY = new LispSymbol(LispNames.APPLY);

	public static final LispSymbol VECTOR = new LispSymbol(LispNames.VECTOR);

	/** What holds a top-level form to the symbols its circle hides. */
	public static final LispSymbol PROGN = new LispSymbol(LispNames.PROGN);

	/**
	 * What a top-level {@code (defparameter NAME (defclass C ...))} assigns once
	 * {@code LispMacroExpander.flattenTopLevel} has split the definer off.
	 */
	public static final LispSymbol FIND_CLASS = new LispSymbol(LispNames.FIND_CLASS);

	/*
	 * What the passes between the macro-time pass and the compilers' resolver write into
	 * a form the source spelled (GrayStreamsLibrary's format rewrite,
	 * LoadFormSubstituter's rebuilt constants): the package the form reads in may shadow
	 * the name -- fset's let is new-let's, coalton's cons is its list constructor -- and
	 * its view of the name does not apply to code it never spelled.
	 */

	public static final LispSymbol LOAD_TIME_VALUE = new LispSymbol(LispNames.LOAD_TIME_VALUE);

	public static final LispSymbol LET = new LispSymbol(LispNames.LET);

	public static final LispSymbol LET_STAR = new LispSymbol(LispNames.LET_STAR);

	public static final LispSymbol IF = new LispSymbol(LispNames.IF);

	public static final LispSymbol FORMAT = new LispSymbol(LispNames.FORMAT);

	public static final LispSymbol MULTIPLE_VALUE_LIST = new LispSymbol(LispNames.MULTIPLE_VALUE_LIST);

	private ReaderSymbols() {
	}

	/**
	 * Returns whether the symbol is one the reader wrote rather than a token it read.
	 * @param symbol the symbol
	 * @return {@code true} for one of this class's instances
	 */
	static boolean contains(LispSymbol symbol) {
		return symbol == QUOTE || symbol == FUNCTION || symbol == LIST || symbol == CONS || symbol == APPEND
				|| symbol == LIST_STAR || symbol == NCONC || symbol == LAMBDA || symbol == FIND_CLASS || symbol == APPLY
				|| symbol == VECTOR || symbol == PROGN || symbol == LOAD_TIME_VALUE || symbol == LET
				|| symbol == LET_STAR || symbol == IF || symbol == FORMAT || symbol == MULTIPLE_VALUE_LIST;
	}

}
