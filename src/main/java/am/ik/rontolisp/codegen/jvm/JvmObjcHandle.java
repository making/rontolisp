package am.ik.rontolisp.codegen.jvm;

/**
 * The compiled program's representation of an Objective-C object or class -- the
 * counterpart of the interpreter's {@code LispObjcObject}, and the one value the compiled
 * value model gains for the {@code objc:} package: a {@code java:} host object is its raw
 * reference, but an Objective-C reference is an address that has to carry its class name
 * (for printing) and its ownership (one retain per wrapper, released by
 * {@link JvmObjcTemplate}'s cleaner). Two wrappers of one object are {@code equal}: the
 * class compares by address, which is what the compiled {@code _equal} falls back to for
 * a value outside the Lisp representation.
 *
 * <p>
 * Travels in the {@code objc:} blob beside {@link JvmObjcTemplate}, renamed into the
 * emitted program's own package by {@link JvmObjcRuntimeBuilder}; hence the same
 * constraints -- no nested classes, no rontolisp import.
 */
final class JvmObjcHandle {

	private final long address;

	private final String className;

	/**
	 * @param address the object's address (never 0; nil is the Lisp {@code null})
	 * @param className the name of the object's class, for printing
	 */
	JvmObjcHandle(long address, String className) {
		this.address = address;
		this.className = className;
	}

	long address() {
		return this.address;
	}

	String className() {
		return this.className;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof JvmObjcHandle that && that.address == this.address;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(this.address);
	}

	/** What the interpreter's wrapper prints. */
	@Override
	public String toString() {
		return "#<objc " + this.className + ">";
	}

}
