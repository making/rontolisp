package am.ik.rontolisp;

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

}
