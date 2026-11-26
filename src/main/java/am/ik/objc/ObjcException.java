package am.ik.objc;

/**
 * What the binding throws when Objective-C cannot do what was asked: the runtime is not
 * on this machine, a class or selector does not exist, an operand does not fit the
 * selector's type, or -- in a native image -- the selector's shape was not registered at
 * build time. Never a silent decline: a window that does not open has no fallback, so
 * every failure here is an error the caller can print or catch.
 */
public class ObjcException extends RuntimeException {

	/**
	 * @param message what went wrong, in the caller's terms
	 */
	public ObjcException(String message) {
		super(message);
	}

	/**
	 * @param message what went wrong
	 * @param cause the underlying failure
	 */
	public ObjcException(String message, Throwable cause) {
		super(message, cause);
	}

}
