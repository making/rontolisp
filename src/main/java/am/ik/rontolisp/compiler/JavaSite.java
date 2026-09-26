package am.ik.rontolisp.compiler;

import org.jspecify.annotations.Nullable;

/**
 * How one {@code java:} call site resolves ({@link JavaSiteResolver}): RESOLVED to one
 * member before it runs, or left to run time. Every backend executes a resolved site as
 * the same explicit request -- the {@link #staticClass} whose members are the candidates
 * and the fully tagged {@link #designator} naming the member -- which is what makes the
 * chosen member the same on the interpreter and in a compiled program. An unresolved site
 * is resolved at run time from the receiver's class and the argument kinds, as
 * {@code java:} always was, and {@link #reason} says why it could not be resolved before.
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
 * @param reason why an unresolved site is resolved at run time, or {@code null} for a
 * resolved one
 */
public record JavaSite(Operator operator, @Nullable String staticClass, @Nullable String designator,
		@Nullable JavaExecutable executable, @Nullable JavaField field, boolean packed, JavaStaticType result,
		@Nullable String reason) {

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
		return new JavaSite(operator, null, null, null, null, false, result, reason);
	}

}
