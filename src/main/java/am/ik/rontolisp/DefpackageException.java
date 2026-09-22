package am.ik.rontolisp;

import org.jspecify.annotations.Nullable;

/**
 * A {@code defpackage} clause violation, typed with the condition Common Lisp names for
 * it. At top level it is still a read/compile-time failure like any
 * {@link LispPackageException}; a {@code defpackage} registering at run time (not a
 * top-level form) is turned by the evaluator into that condition, which a handler can
 * catch.
 */
public final class DefpackageException extends LispPackageException {

	/** The condition class a runtime registration signals. */
	public enum Kind {

		/** {@code package-error}, carrying {@link #designator()} in its package slot. */
		PACKAGE_ERROR,

		/** {@code program-error}: the form itself is ill-formed. */
		PROGRAM_ERROR

	}

	private final Kind kind;

	private final String designator;

	private final @Nullable String missingSymbol;

	private DefpackageException(String message, Kind kind, String designator, @Nullable String missingSymbol) {
		super(message);
		this.kind = kind;
		this.designator = designator;
		this.missingSymbol = missingSymbol;
	}

	/**
	 * A malformed form: a repeated or ill-formed option, an unknown option, or clauses
	 * that must be disjoint naming one symbol.
	 * @param message the reason text
	 * @return the exception
	 */
	public static DefpackageException programError(String message) {
		return new DefpackageException(message, Kind.PROGRAM_ERROR, "", null);
	}

	/**
	 * A package-level failure: a missing package, a nickname collision.
	 * @param message the reason text
	 * @param designator the package for the condition's package slot
	 * @return the exception
	 */
	public static DefpackageException packageError(String message, String designator) {
		return new DefpackageException(message, Kind.PACKAGE_ERROR, designator, null);
	}

	/**
	 * An {@code :import-from} / {@code :shadowing-import-from} name the source package
	 * does not make accessible -- the correctable case: continuing interns the name in
	 * the source package.
	 * @param message the reason text
	 * @param sourcePackage the source package's canonical name
	 * @param symbolName the missing name
	 * @return the exception
	 */
	public static DefpackageException missingSymbol(String message, String sourcePackage, String symbolName) {
		return new DefpackageException(message, Kind.PACKAGE_ERROR, sourcePackage, symbolName);
	}

	/**
	 * The condition class to signal.
	 * @return the kind
	 */
	public Kind kind() {
		return this.kind;
	}

	/**
	 * The package for the {@code package-error}'s package slot (empty for a
	 * {@code program-error}).
	 * @return the designator
	 */
	public String designator() {
		return this.designator;
	}

	/**
	 * The name {@link #designator()} lacks, when this is the correctable missing-symbol
	 * case.
	 * @return the missing name, or null
	 */
	public @Nullable String missingSymbol() {
		return this.missingSymbol;
	}

}
