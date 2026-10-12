package am.ik.rontolisp.clojure;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * The host classes the lowering asks about -- a static member's arities, a throwable's
 * chain, whether a name is a class at all -- found among the program's Java classes
 * ({@link ClojureFiles#javaClasses()}), so a class of the program's Java class path
 * lowers as a JDK class does. The classes are those of the lowering in progress on this
 * thread: a lowering runs to completion where it starts, and one nested in it (a
 * namespace a macro loads) brings its own. Outside any lowering they are rontolisp's own,
 * by reflection.
 */
final class ClojureHostClasses {

	private static final ThreadLocal<@Nullable ClojureJavaClasses> CURRENT = new ThreadLocal<>();

	private static final ClojureJavaClasses OWN = new ClojureReflectedClasses(
			ClojureHostClasses.class.getClassLoader());

	private ClojureHostClasses() {
	}

	/**
	 * Runs a lowering with the program's classes.
	 * @param classes the program's Java classes
	 * @param lowering the lowering
	 * @param <T> what it answers
	 * @return its answer
	 */
	static <T> T lowering(ClojureJavaClasses classes, Supplier<T> lowering) {
		ClojureJavaClasses outer = CURRENT.get();
		CURRENT.set(classes);
		try {
			return lowering.get();
		}
		finally {
			if (outer == null) {
				CURRENT.remove();
			}
			else {
				CURRENT.set(outer);
			}
		}
	}

	/**
	 * The class of a binary name among the program's classes.
	 * @param name the binary name
	 * @return the class, or {@code null} when the program's classes have none of that
	 * name
	 */
	static ClojureJavaClasses.@Nullable JavaClass find(String name) {
		ClojureJavaClasses classes = CURRENT.get();
		return (classes != null ? classes : OWN).find(name);
	}

	/**
	 * Whether the program's classes have a class of a binary name.
	 * @param name the binary name
	 * @return whether it resolves
	 */
	static boolean exists(String name) {
		return find(name) != null;
	}

	/**
	 * Whether a value of one class is one of another ({@link Class#isAssignableFrom}),
	 * both found among the program's classes.
	 * @param to the class assigned to
	 * @param from the class of the value
	 * @return whether both resolve and {@code from} is {@code to} or a subtype of it
	 */
	static boolean isAssignable(String to, String from) {
		if (to.equals(from)) {
			return exists(to);
		}
		ClojureJavaClasses.JavaClass target = find(to);
		if (target == null) {
			return false;
		}
		ClojureJavaClasses.JavaClass source = find(from);
		return source != null && target.isAssignableFrom(source);
	}

	/**
	 * Whether a type name is a primitive's ({@code int}, {@code void}).
	 * @param name the type's name, in {@link Class#getName()} spelling
	 * @return whether it names a primitive type
	 */
	static boolean isPrimitive(String name) {
		return switch (name) {
			case "boolean", "byte", "char", "short", "int", "long", "float", "double", "void" -> true;
			default -> false;
		};
	}

	/**
	 * Whether a type name is an array type's ({@code [B}, {@code [Ljava.lang.Object;}).
	 * @param name the type's name, in {@link Class#getName()} spelling
	 * @return whether it names an array type
	 */
	static boolean isArray(String name) {
		return name.startsWith("[");
	}

	/**
	 * The component type's name of an array type's name.
	 * @param name an array type's name ({@code [B}, {@code [[I},
	 * {@code [Ljava.lang.String;})
	 * @return its component's ({@code byte}, {@code [I}, {@code java.lang.String})
	 */
	static String componentType(String name) {
		String rest = name.substring(1);
		return switch (rest.charAt(0)) {
			case 'L' -> rest.substring(1, rest.length() - 1);
			case '[' -> rest;
			case 'Z' -> "boolean";
			case 'B' -> "byte";
			case 'C' -> "char";
			case 'S' -> "short";
			case 'I' -> "int";
			case 'J' -> "long";
			case 'F' -> "float";
			case 'D' -> "double";
			default -> throw new IllegalArgumentException("no array type: " + name);
		};
	}

}
