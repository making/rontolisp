package am.ik.rontolisp;

import org.jspecify.annotations.Nullable;

/**
 * An opaque reference to a host (Java) object, produced by the {@code java} interop
 * package ({@code java:new}, {@code java:call}, ...). It lets rontolisp drive arbitrary
 * Java APIs (Swing, AWT, ...) by reflection. This value exists only at interpreter
 * runtime: it never appears in source, and the JVM-class and WASM backends cannot compile
 * it.
 *
 * @param ref the wrapped host object (never the Lisp nil; null host values surface as
 * {@code LispNil})
 */
public record LispJavaObject(Object ref) implements LispVal {

	@Override
	public String print() {
		return "#<java " + this.ref.getClass().getName() + ">";
	}

	/**
	 * The one object a value is in Java: a host object's own, or what a Lisp value of a
	 * receiver kind ({@code JavaOverloads.isReceiverKind}) converts to for an
	 * {@code Object} parameter -- {@code t} {@code Boolean.TRUE}, the symbol
	 * {@code |false|} ({@link LispNames#JAVA_FALSE}) {@code Boolean.FALSE}, an integer
	 * the narrowest box that holds it, a bignum its {@code BigInteger}, a float its
	 * {@code Double}, a string its {@code String}, a BMP character its {@code Character}
	 * and a supplementary one the {@code Integer} of its code point. The object a
	 * {@code java:call} on the value is made on, and what a host object's {@code equals}
	 * is handed by {@code equal} ({@link LispEquality#equal}). The interpreter's copy of
	 * the rule; the compiled program's are the bridge's {@code receiverObject} and
	 * {@code _jrecv}.
	 * @param value the value
	 * @return the object, or {@code null} for nil, a function and a value of no kind
	 */
	public static @Nullable Object receiverObject(LispVal value) {
		return switch (value) {
			case LispJavaObject host -> host.ref();
			case LispTrue ignored -> Boolean.TRUE;
			case LispSymbol symbol when LispNames.JAVA_FALSE.equals(symbol.name()) -> Boolean.FALSE;
			case LispInteger i -> i.value() == (int) i.value() ? (Object) (int) i.value() : (Object) i.value();
			case LispBigInteger b -> b.value();
			case LispDouble d -> d.value();
			case LispString s -> s.value();
			case LispChar c ->
				Character.isBmpCodePoint(c.codePoint()) ? (Object) (char) c.codePoint() : (Object) c.codePoint();
			default -> null;
		};
	}

}
