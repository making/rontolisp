package am.ik.rontolisp.compiler;

import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * What is known about a {@code java:} argument or receiver before it is evaluated
 * ({@link JavaSiteResolver#typeOf}): the finite set of {@link JavaKind}s its value can
 * have, an upper bound on the class of the host object it is, or nothing.
 */
public sealed interface JavaStaticType {

	/** Nothing is known: any value, a list, or one the bridge never marshals. */
	JavaStaticType UNKNOWN = new Unknown();

	/**
	 * The value has one of these kinds -- a literal, the result of {@code java:new}, a
	 * Java primitive, {@code String} or final class answered by a resolved member.
	 *
	 * @param kinds the possible kinds, at least one
	 */
	record Kinds(Set<JavaKind> kinds) implements JavaStaticType {

		/**
		 * @param kinds the possible kinds
		 */
		public Kinds {
			kinds = Set.copyOf(kinds);
		}

	}

	/**
	 * The value is {@code nil} or a host object whose class is {@code type} or a subclass
	 * of it -- the declared type of a resolved member, or a
	 * {@code (the (java:object "C") x)} / {@code (declare (type (java:object "C") x))}.
	 * The class is known only up to a subclass, so the value's kind is not: it types a
	 * receiver (the members a call resolves against), and an argument only of a
	 * dispatched site, where the kind is read when the call runs.
	 *
	 * @param type the upper bound
	 */
	record Bounded(JavaType type) implements JavaStaticType {
	}

	/** See {@link #UNKNOWN}. */
	record Unknown() implements JavaStaticType {
	}

	/**
	 * The type a {@code java:call} on such a value resolves its method against.
	 * @return the class, or {@code null} when the value is not known to be a host object
	 */
	default @Nullable JavaType receiverClass() {
		if (this instanceof Bounded bounded) {
			return bounded.type();
		}
		if (this instanceof Kinds kinds) {
			JavaType host = null;
			for (JavaKind kind : kinds.kinds()) {
				if (kind instanceof JavaType type) {
					if (host != null) {
						return null;
					}
					host = type;
				}
				else if (kind != JavaKind.Lisp.NIL) {
					return null;
				}
			}
			return host;
		}
		return null;
	}

	/**
	 * The type of a value a member declared as returning {@code type} answers, once the
	 * bridge unmarshalled it: {@code void} is {@code nil}, {@code boolean} is {@code t}
	 * or {@code nil}, a primitive number or character its kind, a box or {@code String}
	 * the same or {@code nil}; an array (a list) and a supertype of a box, of
	 * {@code String} or of an array ({@code Object}, {@code Number},
	 * {@code CharSequence}, ...) -- whose value may have become any of those -- nothing;
	 * a final class exactly that class or {@code nil}; any other class a {@link Bounded
	 * bound}. A class a compiled program cannot name ({@link JavaType#isLinkable()}) is
	 * nothing: a site resolves only through classes it can be compiled against.
	 * @param type the declared type
	 * @param lookup where the boxes and {@code String} are found
	 * @return the static type of the unmarshalled value
	 */
	static JavaStaticType ofDeclared(JavaType type, JavaClassLookup lookup) {
		JavaKind.Lisp nil = JavaKind.Lisp.NIL;
		switch (type.name()) {
			case "void" -> {
				return new Kinds(Set.of(nil));
			}
			case "boolean", "java.lang.Boolean" -> {
				return new Kinds(Set.of(JavaKind.Lisp.T, nil));
			}
			case "byte", "short", "int", "long" -> {
				return new Kinds(Set.of(JavaKind.Lisp.INTEGER));
			}
			case "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long" -> {
				return new Kinds(Set.of(JavaKind.Lisp.INTEGER, nil));
			}
			case "float", "double" -> {
				return new Kinds(Set.of(JavaKind.Lisp.FLOAT));
			}
			case "java.lang.Float", "java.lang.Double" -> {
				return new Kinds(Set.of(JavaKind.Lisp.FLOAT, nil));
			}
			case "char" -> {
				return new Kinds(Set.of(JavaKind.Lisp.CHAR));
			}
			case "java.lang.Character" -> {
				return new Kinds(Set.of(JavaKind.Lisp.CHAR, nil));
			}
			case "java.lang.String" -> {
				return new Kinds(Set.of(JavaKind.Lisp.STRING, JavaKind.Lisp.STRING_1, nil));
			}
			default -> {
			}
		}
		if (type.isArray() || type.isPrimitive() || !type.isLinkable() || becomesLisp(type, lookup)) {
			return UNKNOWN;
		}
		return type.isFinal() ? new Kinds(Set.of(type, nil)) : new Bounded(type);
	}

	/**
	 * The type of the object {@code (java:new "C" ...)} answers: exactly {@code C}, or
	 * the Lisp value an instance of a box or {@code String} unmarshals to (nothing for a
	 * class a compiled program cannot name).
	 * @param type the constructed class
	 * @param lookup where the boxes and {@code String} are found
	 * @return the static type of the unmarshalled instance
	 */
	static JavaStaticType ofConstructed(JavaType type, JavaClassLookup lookup) {
		return switch (type.name()) {
			case "java.lang.Boolean" -> new Kinds(Set.of(JavaKind.Lisp.T, JavaKind.Lisp.NIL));
			case "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long" ->
				new Kinds(Set.of(JavaKind.Lisp.INTEGER));
			case "java.lang.Float", "java.lang.Double" -> new Kinds(Set.of(JavaKind.Lisp.FLOAT));
			case "java.lang.Character" -> new Kinds(Set.of(JavaKind.Lisp.CHAR));
			case "java.lang.String" -> new Kinds(Set.of(JavaKind.Lisp.STRING, JavaKind.Lisp.STRING_1));
			default -> !type.isLinkable() || becomesLisp(type, lookup) ? UNKNOWN : new Kinds(Set.of(type));
		};
	}

	/**
	 * Whether a value of this type may be unmarshalled into something other than a host
	 * object: it is a supertype of a box, of {@code String} or of an array.
	 */
	private static boolean becomesLisp(JavaType type, JavaClassLookup lookup) {
		for (String name : new String[] { "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
				"java.lang.Long", "java.lang.Float", "java.lang.Double", "java.lang.Character", "java.lang.String",
				"[I" }) {
			JavaType unmarshalled = lookup.find(name);
			if (unmarshalled != null && type.isAssignableFrom(unmarshalled)) {
				return true;
			}
		}
		return false;
	}

}
