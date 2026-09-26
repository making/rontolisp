package am.ik.rontolisp.compiler;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * How one {@code java:} call site resolves ({@link JavaSiteResolver}): RESOLVED before it
 * runs -- to one member, or to a DISPATCH among the {@link #overloads} of its static
 * class -- or left to run time. Every backend executes a resolved site the same way: the
 * receiver must be an instance of the {@link #staticClass}, each argument must be what
 * the resolution counted on ({@link Argument}), then the member -- fully named by the
 * {@link #designator}, or the cheapest of the {@link #overloads} for the arguments'
 * run-time kinds ({@link JavaOverloads#selectRanked}) -- is called with each argument
 * converted to its parameter type. That is what makes the chosen member, and what happens
 * to a value a declaration lied about, the same on the interpreter and in a compiled
 * program. An unresolved site is resolved at run time from the receiver's class and the
 * argument kinds, as {@code java:} always was, and {@link #reason} says why it could not
 * be resolved before.
 *
 * @param operator which {@code java:} operator
 * @param staticClass for a resolved site, the binary name of the class whose members were
 * the candidates: the {@code java:new} / {@code java:static} class, or the static type of
 * a {@code java:call} / {@code java:field} receiver
 * @param designator for a resolved site, the member: a method or constructor designator
 * with every parameter type tagged ({@code "max(int,int)"},
 * {@code "java.lang.StringBuilder(int)"}), or a field name; for a dispatched site the
 * designator as written, which a call no overload accepts is reported with
 * @param executable the resolved method or constructor, or {@code null}
 * @param field the resolved field, or {@code null}
 * @param packed whether the resolved call packs its trailing arguments into a varargs
 * array
 * @param result the static type of the site's value
 * @param arguments for a resolved method or constructor site, what was known about each
 * argument, in order; empty otherwise
 * @param reason why an unresolved site is resolved at run time, or {@code null} for a
 * resolved one
 * @param overloads for a dispatched site, the overloads of the static class the call
 * chooses among when it runs, in {@link JavaOverloads#ranked} order; empty otherwise
 */
public record JavaSite(Operator operator, @Nullable String staticClass, @Nullable String designator,
		@Nullable JavaExecutable executable, @Nullable JavaField field, boolean packed, JavaStaticType result,
		List<Argument> arguments, @Nullable String reason, List<JavaOverloads.Overload> overloads) {

	/**
	 * Copies the arguments and the overloads.
	 */
	public JavaSite {
		arguments = List.copyOf(arguments);
		overloads = List.copyOf(overloads);
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
	 * What a resolved site counted on for one argument: a closed set of {@link #kinds} --
	 * every one of which selects a single-member site's member -- or, at a dispatched
	 * site, the {@link #bound} class the value is an instance of (or {@code nil}), or
	 * nothing. A value outside what the argument promises -- which only a false
	 * {@code (the (java:object "C") x)} can deliver -- is an error where it meets the
	 * member, never converted into something the member was not chosen for.
	 *
	 * @param kinds the kinds the argument's value can have, in a fixed order (the order
	 * every backend tests them in); empty when its kind is known only when it runs
	 * @param declared the class a {@code (the (java:object "C") ...)} around the argument
	 * names, which the error for a value of another kind cites, or {@code null} when the
	 * kinds come from the form itself (a literal, a {@code java:new}, a resolved call)
	 * @param bound for an argument without {@link #kinds}, the class its value is an
	 * instance of when it is not {@code nil}, or {@code null} when it may be any value
	 */
	public record Argument(List<JavaKind> kinds, @Nullable String declared, @Nullable String bound) {

		private static final java.util.Set<String> PRIMITIVES = java.util.Set.of("boolean", "byte", "char", "short",
				"int", "long", "float", "double");

		/**
		 * Copies the kinds.
		 */
		public Argument {
			kinds = List.copyOf(kinds);
		}

		/**
		 * An argument of a closed set of kinds.
		 * @param kinds the kinds, at least one
		 * @param declared the declared class, or {@code null}
		 */
		public Argument(List<JavaKind> kinds, @Nullable String declared) {
			this(kinds, declared, null);
		}

		/**
		 * An argument known only when it runs: any value, or an instance of a bound.
		 * @param bound the class its value is an instance of (or {@code nil}), or
		 * {@code null} for any value
		 * @param declared the declared class, or {@code null}
		 * @return the argument
		 */
		public static Argument open(@Nullable String bound, @Nullable String declared) {
			return new Argument(List.of(), declared, bound);
		}

		/**
		 * @return whether the argument's kind is one of a closed set known before it runs
		 */
		public boolean known() {
			return !this.kinds.isEmpty();
		}

		/**
		 * @return whether the value may be a function, which becomes a proxy where an
		 * interface is expected
		 */
		public boolean mayBeFunction() {
			return known() ? this.kinds.contains(JavaKind.Lisp.FUNCTION) : this.bound == null;
		}

		/**
		 * @return whether the value may be a string: a mutable character vector is
		 * rendered to the string it spells before the member sees it
		 */
		public boolean mayBeString() {
			return known() ? this.kinds.contains(JavaKind.Lisp.STRING) || this.kinds.contains(JavaKind.Lisp.STRING_1)
					: this.bound == null;
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
			if (!known()) {
				return this.bound != null ? "a " + this.bound : "any value";
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
	 * @return whether the site resolved before it runs: to one member, or to a dispatch
	 */
	public boolean resolved() {
		return this.reason == null;
	}

	/**
	 * @return whether the site is resolved to a dispatch among {@link #overloads}: its
	 * member is chosen when it runs, from the arguments' kinds, without reflection
	 */
	public boolean dispatched() {
		return !this.overloads.isEmpty();
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
		return new JavaSite(operator, null, null, null, null, false, result, List.of(), reason, List.of());
	}

}
