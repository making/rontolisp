package am.ik.rontolisp;

/**
 * A raw C pointer, produced by the {@code ffi} package ({@code ffi:alloc},
 * {@code ffi:symbol}, a {@code :pointer} return of {@code ffi:call}, ...). Its own value
 * rather than a plain integer so that {@code ffi:pointerp} answers {@code nil} for
 * {@code 42} and a type error at the foreign boundary stays a type error; the
 * {@link LispObjcObject} shape, minus the class name and the ownership -- foreign memory
 * is {@code malloc}'d and lives until {@code ffi:free}, exactly the contract a C binding
 * expects. Like {@link LispObjcObject} it exists only at interpreter runtime: it never
 * appears in source, and no WASM backend can lower it.
 *
 * <p>
 * Two pointers to one address are {@code equal}: the record compares by address. Address
 * 0 is a legal value (C's {@code NULL}), unlike an Objective-C reference.
 *
 * @param address the pointer's integer address
 */
public record LispForeignPointer(long address) implements LispVal {

	@Override
	public String print() {
		return "#<pointer #x" + Long.toHexString(this.address) + ">";
	}

}
