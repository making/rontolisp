package am.ik.rontolisp;

/**
 * A runtime package failure: {@code make-package} over an existing name,
 * {@code delete-package} / {@code rename-package} over an unknown or read/compile-time
 * package, {@code package-nicknames} over an unknown designator. Separate from
 * {@link LispPackageException} (a read/compile-time failure no handler can catch) so the
 * evaluator can signal it as a catchable {@code package-error} instead, carrying the
 * offending designator (Common Lisp's {@code :package} initarg) for
 * {@code package-error-package}.
 */
public class RuntimePackageException extends RuntimeException {

	private final String designator;

	/**
	 * Creates a new exception with the given message and offending designator.
	 * @param message the detail message
	 * @param designator the offending package designator as given (for the
	 * {@code package-error} {@code :package} slot)
	 */
	public RuntimePackageException(String message, String designator) {
		super(message);
		this.designator = designator;
	}

	/**
	 * The offending package designator as given.
	 * @return the designator for the {@code package-error} {@code :package} slot
	 */
	public String designator() {
		return this.designator;
	}

}
