package am.ik.rontolisp.codegen.jvm;

/**
 * The compiled program's representation of a raw C pointer -- the counterpart of the
 * interpreter's {@code LispForeignPointer}, and the one value the compiled value model
 * gains for the {@code ffi:} package: its own class rather than a plain {@code Long} so
 * that {@code ffi:pointerp} answers {@code nil} for {@code 42} and a type error at the
 * foreign boundary stays a type error. Unlike {@link JvmObjcHandle} it carries no class
 * name and no ownership: foreign memory is {@code malloc}'d and lives until
 * {@code ffi:free}, the contract every C binding expects. Two pointers to one address are
 * {@code equal} (the compiled {@code _equal} falls back to {@code equals} for a value
 * outside the Lisp representation), and address 0 is a legal value (C's {@code NULL}).
 *
 * <p>
 * Travels in the {@code ffi:} blob beside {@link JvmFfiTemplate}, renamed into the
 * emitted program's own package by {@link JvmFfiRuntimeBuilder}; hence the same
 * constraints -- no nested classes, no rontolisp import.
 */
final class JvmFfiHandle {

	private final long address;

	/**
	 * @param address the pointer's integer address (0 is C's NULL)
	 */
	JvmFfiHandle(long address) {
		this.address = address;
	}

	long address() {
		return this.address;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof JvmFfiHandle that && that.address == this.address;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(this.address);
	}

	/** What the interpreter's {@code LispForeignPointer} prints. */
	@Override
	public String toString() {
		return "#<pointer #x" + Long.toHexString(this.address) + ">";
	}

}
