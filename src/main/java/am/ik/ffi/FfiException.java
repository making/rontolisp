package am.ik.ffi;

/**
 * What the binding throws when a foreign call cannot do what was asked: this JVM denies
 * native access, a library will not open, a symbol is not there, a type designator does
 * not exist, an operand does not fit its declared type, or -- in a native image -- the
 * call's shape was not registered at build time. Never a silent decline: every failure
 * here is an error the caller can print or catch.
 */
public class FfiException extends RuntimeException {

	/**
	 * @param message what went wrong, in the caller's terms
	 */
	public FfiException(String message) {
		super(message);
	}

	/**
	 * @param message what went wrong
	 * @param cause the underlying failure
	 */
	public FfiException(String message, Throwable cause) {
		super(message, cause);
	}

}
