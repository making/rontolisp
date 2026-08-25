package am.ik.rontolisp;

/**
 * An opaque reference to an Objective-C object or class, produced by the {@code objc}
 * package ({@code objc:class}, {@code objc:send}, ...) and handed to the AppKit widget
 * layer written over it. Like {@link LispJavaObject} it exists only at interpreter
 * runtime -- it never appears in source; the JVM class output has its own twin,
 * {@code codegen.jvm.JvmObjcHandle}, and no WASM backend can lower it -- but unlike one
 * it needs no reflection, which is what lets it work in the native binary.
 *
 * <p>
 * The interpreter owns ONE retain on every object it wraps (the {@code alloc} /
 * {@code new} / {@code copy} family hands it over, everything else is retained on the way
 * in) and releases it on thread 0 when the value is collected, so a wrapper is valid for
 * as long as Lisp holds it. Two wrappers of one object are {@code equal}: the record
 * compares by address.
 *
 * @param address the object's address (never 0; nil surfaces as {@code LispNil})
 * @param className the name of the object's class, for printing
 */
public record LispObjcObject(long address, String className) implements LispVal {

	@Override
	public String print() {
		return "#<objc " + this.className + ">";
	}

}
