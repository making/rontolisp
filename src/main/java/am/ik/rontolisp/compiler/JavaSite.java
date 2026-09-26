package am.ik.rontolisp.compiler;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * How one {@code java:} call site resolves ({@link JavaSiteResolver}): RESOLVED to one
 * member before it runs, or left to run time. Every backend executes a resolved site the
 * same way: the receiver must be an instance of the {@link #staticClass}, each argument
 * must have one of the kinds the resolution counted on ({@link Argument}), each is
 * converted to the member's parameter type, and the member -- fully named by the
 * {@link #designator} -- is called. That is what makes the chosen member, and what
 * happens to a value a declaration lied about, the same on the interpreter and in a
 * compiled program. An unresolved site is resolved at run time from the receiver's class
 * and the argument kinds, as {@code java:} always was, and {@link #reason} says why it
 * could not be resolved before.
 *
 * @param operator which {@code java:} operator
 * @param staticClass for a resolved site, the binary name of the class whose members were
 * the candidates: the {@code java:new} / {@code java:static} class, or the static type of
 * a {@code java:call} / {@code java:field} receiver
 * @param designator for a resolved site, the member: a method or constructor designator
 * with every parameter type tagged ({@code "max(int,int)"},
 * {@code "java.lang.StringBuilder(int)"}), or a field name
 * @param executable the resolved method or constructor, or {@code null}
 * @param field the resolved field, or {@code null}
 * @param packed whether the resolved call packs its trailing arguments into a varargs
 * array
 * @param result the static type of the site's value
 * @param arguments for a resolved method or constructor site, what was known about each
 * argument, in order; empty otherwise
 * @param reason why an unresolved site is resolved at run time, or {@code null} for a
 * resolved one
 */
public record JavaSite(Operator operator, @Nullable String staticClass, @Nullable String designator,
		@Nullable JavaExecutable executable, @Nullable JavaField field, boolean packed, JavaStaticType result,
		List<Argument> arguments, @Nullable String reason) {

	/**
	 * Copies the arguments.
	 */
	public JavaSite {
		arguments = List.copyOf(arguments);
	}

	/** The {@code java:} operators a site can be. */
	public enum Operator {

		/** {@code java:new}. */
		NEW,

		/** {@code java:call}. */
		CALL,

		/** {@code java:static}. */
		STATIC,

		/** {@code java:field}. */
		FIELD

	}

	/**
	 * What a resolved site counted on for one argument: every kind in {@link #kinds}
	 * selects its member, and a value of any other kind -- which only a false
	 * {@code (the (java:object "C") x)} can deliver -- is an error where it meets the
	 * member, never converted into something the member was not chosen for.
	 *
	 * @param kinds the kinds the argument's value can have, in a fixed order (the order
	 * every backend tests them in)
	 * @param declared the class a {@code (the (java:object "C") ...)} around the argument
	 * names, which the error for a value of another kind cites, or {@code null} when the
	 * kinds come from the form itself (a literal, a {@code java:new}, a resolved call)
	 */
	public record Argument(List<JavaKind> kinds, @Nullable String declared) {

		private static final java.util.Set<String> PRIMITIVES = java.util.Set.of("boolean", "byte", "char", "short",
				"int", "long", "float", "double");

		/**
		 * Copies the kinds.
		 */
		public Argument {
			kinds = List.copyOf(kinds);
		}

		/**
		 * What a value of this argument was declared or known to be, as an error message
		 * says it.
		 * @return e.g. {@code a java.lang.String}, or {@code an integer or nil}
		 */
		public String expected() {
			String declaredClass = this.declared;
			// A primitive declaration ((java:object "int")) reads better as its kinds.
			if (declaredClass != null && !PRIMITIVES.contains(declaredClass)) {
				return "a " + declaredClass;
			}
			List<String> names = new java.util.ArrayList<>();
			for (JavaKind kind : this.kinds) {
				String name = describe(kind);
				if (!names.contains(name)) {
					names.add(name);
				}
			}
			return String.join(" or ", names);
		}

		private static String describe(JavaKind kind) {
			if (kind instanceof JavaType type) {
				return "a " + type.name();
			}
			return switch ((JavaKind.Lisp) kind) {
				case NIL -> "nil";
				case T -> "t";
				case INTEGER -> "an integer";
				case FLOAT -> "a float";
				case STRING, STRING_1 -> "a string";
				case CHAR, SUPPLEMENTARY_CHAR -> "a character";
				case FUNCTION -> "a function";
			};
		}

	}

	/**
	 * @return whether the site resolved to one member before it runs
	 */
	public boolean resolved() {
		return this.reason == null;
	}

	/**
	 * An unresolved site.
	 * @param operator the operator
	 * @param result what is known about its value anyway ({@code java:new} knows its
	 * class)
	 * @param reason why it is resolved at run time
	 * @return the site
	 */
	static JavaSite unresolved(Operator operator, JavaStaticType result, String reason) {
		return new JavaSite(operator, null, null, null, null, false, result, List.of(), reason);
	}

}
