package am.ik.objc;

/**
 * An Objective-C exception raised inside a send or a C call and caught there
 * ({@link ObjcCatch}): the call answered nothing, and {@link #exception()} is what was
 * thrown, RETAINED once for whoever catches this -- the primitive layer hands that
 * reference to the Lisp condition's object.
 */
public final class ObjcRaised extends ObjcException {

	private final long exception;

	/**
	 * @param what the call, in the caller's terms
	 * @param exception the thrown object's address, retained (0 when nil was thrown)
	 */
	public ObjcRaised(String what, long exception) {
		super(what + " raised an Objective-C exception");
		this.exception = exception;
	}

	/**
	 * @return the thrown object's address, retained once (0 when nil was thrown)
	 */
	public long exception() {
		return this.exception;
	}

}
