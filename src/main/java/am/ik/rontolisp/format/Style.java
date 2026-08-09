package am.ik.rontolisp.format;

import org.jspecify.annotations.Nullable;

/**
 * How a form is laid out once it no longer fits on one line.
 * <p>
 * Only two numbers are ever needed: how many arguments stay on the operator's line, and
 * how far the lines after it are indented from the opening paren. Everything else --
 * where function-call arguments align, whether a sequence packs several per line, how a
 * {@code loop} splits into clauses -- follows from {@link Kind}.
 *
 * @param kind the layout family
 * @param inlineArgs how many arguments (not counting the operator) stay on the first
 * line; meaningful for {@link Kind#BODY} and {@link Kind#CLAUSES}. {@link Kind#CLAUSE}
 * and {@link Kind#DEFMETHOD} read the layout's own number off the form instead, and what
 * is stored here is only what {@code statements} is judged against
 * @param bodyIndent how far the lines after the first are indented from the opening
 * paren's column; meaningful for {@link Kind#BODY}, {@link Kind#CLAUSE} and
 * {@link Kind#CLAUSES}
 * @param childStyle the style forced onto certain children, or {@code null}. This is what
 * lets an operator describe the shape of the lists INSIDE it, which is the only way a
 * binding, clause or local-function definition can be recognized -- structurally
 * {@code (rec (list acc) body)} is indistinguishable from a function call, and only
 * {@code labels} knows better. Which children it applies to depends on {@link Kind}:
 * {@link Kind#BODY} gives it to the first argument, {@link Kind#CLAUSES} to every
 * argument past {@code inlineArgs}, {@link Kind#DATA} to all of them.
 * @param statements whether the arguments past {@code inlineArgs} are an implicit
 * {@code progn} -- a SEQUENCE of things done in order. Two or more of those always get a
 * line each, however short they are, for the same reason no formatter of a C-like
 * language will put two statements on one line; it also keeps the output stable, since a
 * two-form body must not silently join when a rename makes it two characters shorter. It
 * is false wherever the trailing arguments are ALTERNATIVES or OPTIONS instead, which
 * stay on one line while they fit: {@code if}'s two branches, {@code cond}'s clauses,
 * {@code defvar}'s value and docstring, {@code defstruct}'s slots, a {@code let}
 * binding's init form.
 */
public record Style(Kind kind, int inlineArgs, int bodyIndent, @Nullable Style childStyle, boolean statements) {

	/** The layout families. */
	public enum Kind {

		/**
		 * A function call: the first argument stays on the operator's line and the rest
		 * align under it. The trailing {@code :key value} pairs, if any, are kept
		 * together and given a line each.
		 */
		CALL,
		/**
		 * A sequence with no operator (a literal, a binding list, a {@code cond} clause):
		 * every element aligns just inside the opening delimiter.
		 */
		DATA,
		/**
		 * An operator with a fixed number of distinguished arguments followed by a body:
		 * {@code inlineArgs} arguments stay on the first line, the body forms get a line
		 * each at {@code bodyIndent}.
		 */
		BODY,
		/**
		 * Like {@link #BODY}, but every argument past {@code inlineArgs} is a clause and
		 * is laid out with {@code childStyle} rather than by its own head symbol.
		 */
		CLAUSES,
		/**
		 * One {@code cond}/{@code case} clause: a predicate, then a body at 1. Like
		 * {@link #BODY}, except that whether the body starts on the predicate's line is
		 * decided per clause rather than fixed here -- a bare {@code (t ...)} keeps a
		 * single body form beside it where a real test does not
		 * ({@code LispFormatter.clauseInlineArgs}).
		 */
		CLAUSE,
		/**
		 * {@code do}/{@code do*}: the variable list stays on the first line, the end-test
		 * clause gets a line of its own indented PAST the body, and the body follows at
		 * 2. The extra indent is what keeps the end test from reading as a body form.
		 */
		DO,
		/**
		 * The extended {@code loop}: one line per clause, aligned under the first clause.
		 * A simple {@code loop} (no leading loop keyword) falls back to a plain body.
		 */
		LOOP,
		/**
		 * {@code defmethod}: like {@link #BODY}, except the number of distinguished
		 * arguments is found rather than fixed -- everything up to and including the
		 * specialized lambda list (the first list argument) stays on the first line, so
		 * qualifiers like {@code :around} do not push it onto a line of its own.
		 */
		DEFMETHOD

	}

	/**
	 * The style of a function call.
	 * @return the style
	 */
	public static Style call() {
		return new Style(Kind.CALL, 0, 0, null, false);
	}

	/**
	 * The style of {@code cond}: laid out like a call -- the first clause stays on the
	 * {@code cond}'s line and the rest align under it -- but every argument is a clause
	 * and two clauses never share a line.
	 * @param clauseStyle the style forced onto every clause
	 * @return the style
	 */
	public static Style call(Style clauseStyle) {
		return new Style(Kind.CALL, 0, 0, clauseStyle, false);
	}

	/**
	 * The style of an operator-less sequence.
	 * @return the style
	 */
	public static Style data() {
		return new Style(Kind.DATA, 0, 0, null, false);
	}

	/**
	 * The style of an operator-less sequence whose elements all have a known shape.
	 * @param elementStyle the style forced onto every element
	 * @return the style
	 */
	public static Style data(Style elementStyle) {
		return new Style(Kind.DATA, 0, 0, elementStyle, false);
	}

	/**
	 * The style of an operator with distinguished arguments followed by a body of
	 * statements.
	 * @param inlineArgs how many arguments stay on the operator's line
	 * @param bodyIndent how far the body is indented from the opening paren
	 * @return the style
	 */
	public static Style body(int inlineArgs, int bodyIndent) {
		return new Style(Kind.BODY, inlineArgs, bodyIndent, null, true);
	}

	/**
	 * The style of an operator whose trailing arguments are alternatives or options
	 * rather than a sequence, so two of them may share a line: {@code if},
	 * {@code defvar}, {@code defstruct}, a {@code let} binding.
	 * @param inlineArgs how many arguments stay on the operator's line
	 * @param bodyIndent how far the remaining arguments are indented from the opening
	 * paren
	 * @return the style
	 */
	public static Style operands(int inlineArgs, int bodyIndent) {
		return new Style(Kind.BODY, inlineArgs, bodyIndent, null, false);
	}

	/**
	 * The style of an operator whose first argument is a list of a known shape (a
	 * {@code let} binding list, a {@code flet} definition list).
	 * @param inlineArgs how many arguments stay on the operator's line
	 * @param bodyIndent how far the body is indented from the opening paren
	 * @param elementStyle the style forced onto every element of the first argument
	 * @return the style
	 */
	public static Style body(int inlineArgs, int bodyIndent, Style elementStyle) {
		return new Style(Kind.BODY, inlineArgs, bodyIndent, data(elementStyle), true);
	}

	/**
	 * The style of an operator followed by clauses.
	 * @param inlineArgs how many arguments precede the clauses
	 * @param clauseStyle the style forced onto every clause
	 * @return the style
	 */
	public static Style clauses(int inlineArgs, Style clauseStyle) {
		return new Style(Kind.CLAUSES, inlineArgs, 2, clauseStyle, false);
	}

	/**
	 * The style of one clause: a predicate, then a body at 1. How much of it stays on the
	 * predicate's line is read off the clause rather than fixed here.
	 * @return the style
	 */
	public static Style clause() {
		return new Style(Kind.CLAUSE, 0, 1, null, true);
	}

	/**
	 * The {@code do}/{@code do*} style.
	 * @return the style
	 */
	public static Style iteration() {
		return new Style(Kind.DO, 1, 2, null, true);
	}

	/**
	 * The extended-{@code loop} style. A {@code loop} one-liner is idiomatic however many
	 * clauses it has, so its clauses are not treated as statements.
	 * @return the style
	 */
	public static Style loop() {
		return new Style(Kind.LOOP, 0, 2, null, false);
	}

	/**
	 * The {@code defmethod} style.
	 * @return the style
	 */
	public static Style defmethod() {
		return new Style(Kind.DEFMETHOD, 2, 2, null, true);
	}

}
