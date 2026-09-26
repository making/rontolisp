package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * How a {@code java:reify} or a {@code java:proxy} implements its interface
 * ({@link JavaImplementations}): the methods an implementing class declares -- each
 * {@link Slot} calls one of the form's functions, or, for an abstract method no function
 * implements, throws -- chosen before the form runs, the same on every backend. The
 * interpreter answers them from a {@link java.lang.reflect.Proxy} handler that dispatches
 * on exactly these slots; a compiled program declares them in a generated class
 * ({@code codegen.jvm.JvmJavaImplementations}). A default method no slot overrides keeps
 * its body, and {@code equals}/{@code hashCode} not implemented are {@code Object}'s
 * (identity) -- on both.
 *
 * @param proxy whether it is a {@code java:proxy}: every method, default ones too, calls
 * the one function with the method's name before its arguments
 * @param iface the interface, or {@code null} for an unresolved form
 * @param slots the methods the implementing class declares, in a fixed order
 * @param reason why an unresolved form is resolved when it runs, or {@code null}
 */
public record JavaImplementation(boolean proxy, @Nullable JavaType iface, List<Slot> slots, @Nullable String reason) {

	/** The {@link Slot#implementation} of an abstract method no function implements. */
	public static final int NONE = -1;

	/**
	 * Copies the slots.
	 */
	public JavaImplementation {
		slots = List.copyOf(slots);
	}

	/**
	 * One method the implementing class declares: one per parameter list and return type
	 * (a covariant variant is a slot of its own, implemented by the same function).
	 *
	 * @param name the method name
	 * @param parameterTypes its parameter types
	 * @param returnType its return type
	 * @param implementation which function implements it: the index of the
	 * {@code java:reify} designator that names it, {@code 0} for a {@code java:proxy}'s
	 * callable, or {@link #NONE}: an abstract method, which throws
	 * {@link UnsupportedOperationException} with {@link #noImplementation}
	 */
	public record Slot(String name, List<JavaType> parameterTypes, JavaType returnType, int implementation) {

		/**
		 * Copies the parameter types.
		 */
		public Slot {
			parameterTypes = List.copyOf(parameterTypes);
		}

		/**
		 * @return the method's name and parameter types, {@code name(p1,p2)} in
		 * {@link Class#getName()} spelling: what a designator names and a message shows
		 */
		public String key() {
			return JavaImplementation.key(this.name, this.parameterTypes);
		}

		/**
		 * @return {@link #key()} followed by the return type: what an invocation is
		 * dispatched by -- a covariant variant is a method of its own
		 */
		public String dispatchKey() {
			return key() + this.returnType.name();
		}

	}

	/**
	 * @return whether the form's methods were chosen before it runs
	 */
	public boolean resolved() {
		return this.reason == null;
	}

	/**
	 * The dispatch key of a method.
	 * @param name the method name
	 * @param parameterTypes its parameter types
	 * @return {@code name(p1,p2)}
	 */
	public static String key(String name, List<? extends JavaType> parameterTypes) {
		List<String> names = new ArrayList<>();
		for (JavaType type : parameterTypes) {
			names.add(type.name());
		}
		return name + "(" + String.join(",", names) + ")";
	}

	/**
	 * @return whether a slot implements {@code toString()}; otherwise the object's
	 * {@code toString} answers {@link #defaultToString()}
	 */
	public boolean declaresToString() {
		for (Slot slot : this.slots) {
			if ("toString".equals(slot.name()) && slot.parameterTypes().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return what {@code toString} answers when no slot implements it:
	 * {@code #<java-reify I>} or {@code #<java-proxy I>}
	 */
	public String defaultToString() {
		return defaultToString(this.proxy, java.util.Objects.requireNonNull(this.iface).name());
	}

	/**
	 * What the object's {@code toString} answers when no function implements it.
	 * @param proxy whether it is a {@code java:proxy}
	 * @param iface the interface name
	 * @return {@code #<java-reify I>} or {@code #<java-proxy I>}
	 */
	public static String defaultToString(boolean proxy, String iface) {
		return "#<java-" + (proxy ? "proxy " : "reify ") + iface + ">";
	}

	/**
	 * The message of the {@link UnsupportedOperationException} an abstract method no
	 * function implements throws.
	 * @param iface the interface name
	 * @param key the method's {@link Slot#key()}
	 * @return {@code java:reify: no implementation of I.m(p1,p2)}
	 */
	public static String noImplementation(String iface, String key) {
		return "java:reify: no implementation of " + iface + "." + key;
	}

	/**
	 * The text before the value in the error a function's value that does not convert to
	 * the method's return type raises: {@code java:proxy: cannot return } -- the value
	 * follows, then {@link #returnMismatchSuffix}.
	 * @param proxy whether it is a {@code java:proxy}
	 * @return the prefix
	 */
	public static String returnMismatchPrefix(boolean proxy) {
		return proxy ? "java:proxy: cannot return " : "java:reify: cannot return ";
	}

	/**
	 * The text after the value in that error: {@code  as class java.lang.String from I}
	 * (a {@code java:reify} names the method too: {@code from I.m}).
	 * @param proxy whether it is a {@code java:proxy}
	 * @param iface the interface name
	 * @param method the method name
	 * @param returnType the method's return type
	 * @return the suffix
	 */
	public static String returnMismatchSuffix(boolean proxy, String iface, String method, JavaType returnType) {
		return " as " + classText(returnType) + " from " + iface + (proxy ? "" : "." + method);
	}

	/**
	 * A type as {@link Class#toString()} spells it: {@code int}, {@code class C},
	 * {@code interface I}.
	 * @param type the type
	 * @return the text
	 */
	public static String classText(JavaType type) {
		if (type.isPrimitive()) {
			return type.name();
		}
		return (type.isInterface() ? "interface " : "class ") + type.name();
	}

}
