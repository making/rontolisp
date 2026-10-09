package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * How a {@code java:reify}, a {@code java:proxy} or a {@code java:subclass} implements
 * its interfaces -- a {@code java:reify} one, a {@code java:proxy} one or more
 * ({@link JavaImplementations}): the methods an implementing class declares -- each
 * {@link Slot} calls one of the form's functions, or, for an abstract method no function
 * implements, throws -- chosen before the form runs, the same on every backend. The
 * interpreter answers them from a {@link java.lang.reflect.Proxy} handler that dispatches
 * on exactly these slots (a {@code java:subclass} from a generated subclass instead); a
 * compiled program declares them in a generated class
 * ({@code codegen.jvm.JvmJavaImplementations}). A default method no slot overrides keeps
 * its body, and {@code equals}/{@code hashCode} not implemented are {@code Object}'s
 * (identity) -- on both. A {@code java:subclass} overrides like any other class method,
 * including {@code Object}'s three, and inherits what no slot overrides.
 *
 * @param proxy whether it is a {@code java:proxy}: every method, default ones too, calls
 * the one function with the method's name before its arguments (a {@code java:subclass}'s
 * callable takes the object first, then the name)
 * @param interfaces the interfaces, in the form's order; empty for an unresolved form
 * (for a {@code java:subclass}, the extra interfaces beside its superclass)
 * @param slots the methods the implementing class declares, in a fixed order
 * @param reason why an unresolved form is resolved when it runs, or {@code null}
 * @param superclass the superclass a {@code java:subclass} extends, or {@code null} for a
 * {@code java:reify} / {@code java:proxy}
 * @param markers the keywords the form -- or the call converting a function -- ends in
 * ({@link JavaMarkers}): with {@code :java-false} an argument Java hands a slot's
 * function answers Java's {@code false} as {@code |false|}; with {@code :functional} too,
 * a function implementing {@code java.util.Comparator} may answer a boolean
 * ({@link #readsComparison})
 */
public record JavaImplementation(boolean proxy, List<JavaType> interfaces, List<Slot> slots, @Nullable String reason,
		@Nullable JavaType superclass, JavaMarkers markers) {

	/** The {@link Slot#implementation} of an abstract method no function implements. */
	public static final int NONE = -1;

	/**
	 * Copies the interfaces and the slots.
	 */
	public JavaImplementation {
		interfaces = List.copyOf(interfaces);
		slots = List.copyOf(slots);
	}

	/**
	 * A {@code java:reify} / {@code java:proxy} implementation (no superclass) ending in
	 * no marker.
	 * @param proxy whether it is a {@code java:proxy}
	 * @param interfaces the interfaces, in the form's order
	 * @param slots the methods the implementing class declares
	 * @param reason why an unresolved form is resolved when it runs, or {@code null}
	 */
	public JavaImplementation(boolean proxy, List<JavaType> interfaces, List<Slot> slots, @Nullable String reason) {
		this(proxy, interfaces, slots, reason, null, JavaMarkers.NONE);
	}

	/**
	 * An implementation ending in no marker.
	 * @param proxy whether it is a {@code java:proxy}
	 * @param interfaces the interfaces, in the form's order
	 * @param slots the methods the implementing class declares
	 * @param reason why an unresolved form is resolved when it runs, or {@code null}
	 * @param superclass the superclass a {@code java:subclass} extends, or {@code null}
	 */
	public JavaImplementation(boolean proxy, List<JavaType> interfaces, List<Slot> slots, @Nullable String reason,
			@Nullable JavaType superclass) {
		this(proxy, interfaces, slots, reason, superclass, JavaMarkers.NONE);
	}

	/**
	 * @param ending the markers the form ends in
	 * @return this implementation, ending in them
	 */
	public JavaImplementation withMarkers(JavaMarkers ending) {
		return new JavaImplementation(this.proxy, this.interfaces, this.slots, this.reason, this.superclass, ending);
	}

	/**
	 * @return whether an argument Java hands a slot's function answers Java's
	 * {@code false} as {@code |false|} ({@code :java-false})
	 */
	public boolean javaFalse() {
		return this.markers.javaFalse();
	}

	/**
	 * Whether a slot's function answers as a comparison: the function a call ending in
	 * {@code :functional} and {@code :java-false} converts to
	 * {@code java.util.Comparator} implements {@code compare}, and may answer a boolean
	 * as Clojure's {@code AFunction.compare} reads one -- {@code t} is {@code -1},
	 * {@code |false|} {@code 1} when the function answers true for the arguments swapped
	 * and {@code 0} otherwise -- or any real number, whose {@code intValue} it is (a
	 * float or ratio truncated, an integer's low 32 bits). A {@code java:proxy}'s
	 * callable and a {@code java:subclass}'s are called with the method's name: neither
	 * reads one.
	 * @param slot one of the slots
	 * @return whether its function's answer is read so
	 */
	public boolean readsComparison(Slot slot) {
		return !this.proxy && this.superclass == null && this.markers.functional() && this.markers.javaFalse()
				&& slot.implementation() != NONE && "int".equals(slot.returnType().name())
				&& COMPARATOR_COMPARE.equals(slot.key()) && this.interfaces.size() == 1
				&& COMPARATOR.equals(this.interfaces.get(0).name());
	}

	/** The interface whose {@code compare} a function may answer a boolean for. */
	public static final String COMPARATOR = "java.util.Comparator";

	/** {@code Comparator.compare}'s {@link Slot#key()}. */
	public static final String COMPARATOR_COMPARE = "compare(java.lang.Object,java.lang.Object)";

	/**
	 * @return whether this is a {@code java:subclass} (which extends a superclass)
	 */
	public boolean isSubclass() {
		return this.superclass != null;
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
	 * @return the interface names as the object's {@code toString} and the messages spell
	 * them ({@link #interfaceNames(List)})
	 */
	public String interfaceNames() {
		return interfaceNames(this.interfaces);
	}

	/**
	 * Interfaces as the object's {@code toString} and the messages spell them: the names
	 * in the form's order, separated by a space.
	 * @param interfaces the interfaces
	 * @return e.g. {@code java.lang.Runnable java.util.function.Supplier}
	 */
	public static String interfaceNames(List<? extends JavaType> interfaces) {
		List<String> names = new ArrayList<>();
		for (JavaType type : interfaces) {
			names.add(type.name());
		}
		return String.join(" ", names);
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
	 * {@code #<java-reify I>} or {@code #<java-proxy I J>}
	 */
	public String defaultToString() {
		return defaultToString(this.proxy, interfaceNames());
	}

	/**
	 * What the object's {@code toString} answers when no function implements it.
	 * @param proxy whether it is a {@code java:proxy}
	 * @param iface the interface names ({@link #interfaceNames(List)})
	 * @return {@code #<java-reify I>} or {@code #<java-proxy I J>}
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
	 * The text before the value in the error a {@code java:subclass} function's value
	 * that does not convert to the method's return type raises:
	 * {@code java:subclass: cannot return } -- the value follows, then
	 * {@link #subclassReturnMismatchSuffix}.
	 * @return the prefix
	 */
	public static String subclassReturnMismatchPrefix() {
		return "java:subclass: cannot return ";
	}

	/**
	 * The text after the value in that error: {@code  as class java.lang.String from S
	 * I...}.
	 * @param superclass the superclass
	 * @param interfaces the extra interfaces
	 * @param returnType the method's return type
	 * @return the suffix
	 */
	public static String subclassReturnMismatchSuffix(JavaType superclass, List<JavaType> interfaces,
			JavaType returnType) {
		String ifaces = interfaces.isEmpty() ? "" : " " + interfaceNames(interfaces);
		return " as " + classText(returnType) + " from " + superclass.name() + ifaces;
	}

	/**
	 * The text after the value in that error: {@code  as class java.lang.String from I}
	 * (a {@code java:reify} names the method too: {@code from I.m}).
	 * @param proxy whether it is a {@code java:proxy}
	 * @param iface the interface names ({@link #interfaceNames(List)})
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
