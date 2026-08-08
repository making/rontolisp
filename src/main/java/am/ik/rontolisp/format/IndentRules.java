package am.ik.rontolisp.format;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The operator-to-{@link Style} table: what a form looks like when it has to break.
 * <p>
 * The table is the whole of the formatter's knowledge about the language. It is
 * deliberately a static table rather than a lookup into the evaluator's namespaces:
 * formatting stays a purely syntactic transformation, so a file can be formatted without
 * loading it, without its dependencies being present, and without its {@code defpackage}
 * having been evaluated. (This is the one place rontolisp's formatter differs in KIND
 * from trivial-formatter, which has to {@code asdf:load-system} a system before it can
 * print it, because it delegates indentation to the host's pretty-printer dispatch.)
 * <p>
 * An operator that is NOT in the table gets {@link Style.Kind#CALL} -- which is the right
 * answer for every function, and the reason the table only needs the operators whose body
 * is not an argument list.
 */
public final class IndentRules {

	private IndentRules() {
	}

	/**
	 * A list whose head is the subject and whose remaining elements line up one column
	 * past the opening paren: a {@code let} binding, a {@code do} step form. The head may
	 * be a symbol, so without this style such a list would be mistaken for a function
	 * call and its init form would align under the variable's own width instead.
	 */
	private static final Style BINDING = Style.operands(0, 1);

	/**
	 * A {@code cond}/{@code case} clause: same shape as a {@link #BINDING} but its tail
	 * IS a body, so two forms in it never share a line.
	 */
	private static final Style CLAUSE = Style.body(0, 1);

	/**
	 * A list shaped like a definition -- name, lambda list, then a body at 2: a
	 * {@code flet}/{@code labels}/{@code macrolet} local function, a
	 * {@code handler-case}/{@code restart-case} clause.
	 */
	private static final Style DEFINITION = Style.body(1, 2);

	private static final Map<String, Style> RULES = rules();

	private static Map<String, Style> rules() {
		Map<String, Style> rules = new HashMap<>();
		// Definitions: name and lambda list on the first line, body at 2.
		for (String name : List.of("defun", "defmacro", "define-compiler-macro", "define-setf-expander", "defsetf",
				"deftype", "async-defun", "prog2", "progv", "destructuring-bind", "multiple-value-bind", "with-slots",
				"with-accessors", "with-package-iterator")) {
			rules.put(name, Style.body(2, 2));
		}
		rules.put("defmethod", Style.defmethod());
		// One distinguished argument, then a body at 2.
		for (String name : List.of("lambda", "async-lambda", "when", "unless", "while", "block", "catch",
				"unwind-protect", "prog1", "multiple-value-prog1", "dolist", "dotimes", "do-symbols",
				"do-external-symbols", "do-all-symbols", "with-open-file", "with-open-stream", "with-input-from-string",
				"with-output-to-string", "with-simple-restart", "with-mutex", "with-lock-held", "with-arena",
				"eval-when", "with-hash-table-iterator", "print-unreadable-object", "pprint-logical-block",
				// (uiop:with-deprecation (LEVEL) definitions...): the level list is the
				// distinguished argument. Spelled out rather than left to the with-
				// guess.
				"with-deprecation")) {
			rules.put(name, Style.body(1, 2));
		}
		// No distinguished argument: every subform is a body form.
		for (String name : List.of("progn", "locally", "tagbody", "ignore-errors", "time", "with-standard-io-syntax")) {
			rules.put(name, Style.body(0, 2));
		}
		// Operator plus operands or options -- no body, so a short one stays on one line
		// however many of them it has.
		for (String name : List.of("defvar", "defparameter", "defconstant", "defstruct", "defpackage", "defsystem",
				"multiple-value-setq")) {
			rules.put(name, Style.operands(1, 2));
		}
		for (String name : List.of("defclass", "define-condition", "defgeneric")) {
			rules.put(name, Style.operands(2, 2));
		}
		// (if test then else): the branches line up under the test, not under the body
		// indent, so a two-armed if never reads as a three-form body.
		rules.put("if", Style.operands(1, 4));
		// Binding forms: the binding list is a sequence of same-shaped lists. The
		// if-let/when-let family takes one too, and its tail is a body (if-let's is the
		// then/else pair) -- laid out as a call, the whole body would align under the
		// binding list's own width.
		for (String name : List.of("let", "let*", "symbol-macrolet", "handler-bind", "restart-bind", "if-let",
				"when-let", "when-let*")) {
			rules.put(name, Style.body(1, 2, BINDING));
		}
		for (String name : List.of("flet", "labels", "macrolet")) {
			rules.put(name, Style.body(1, 2, DEFINITION));
		}
		// Clause forms. cond has no keyform, so its clauses align under the first one
		// rather than at a body indent -- the one clause form laid out like a call.
		rules.put("cond", Style.call(CLAUSE));
		for (String name : List.of("case", "ccase", "ecase", "typecase", "etypecase", "ctypecase")) {
			rules.put(name, Style.clauses(1, CLAUSE));
		}
		for (String name : List.of("handler-case", "restart-case")) {
			rules.put(name, Style.clauses(1, DEFINITION));
		}
		rules.put("do", Style.iteration());
		rules.put("do*", Style.iteration());
		rules.put("loop", Style.loop());
		return rules;
	}

	/**
	 * The style a listing is laid out with when it does not fit on one line.
	 * @param listing the listing
	 * @return the style
	 */
	public static Style styleFor(CstNode.Listing listing) {
		// A literal (#(, #S(, #2A(, ...) has no operator position at all.
		if (!"(".equals(listing.open())) {
			return Style.data();
		}
		List<CstNode> items = listing.items();
		if (items.isEmpty() || !(items.get(0) instanceof CstNode.Atom head)) {
			// ((lambda (x) x) 1), a binding list, an alist: no operator to look up.
			return Style.data();
		}
		if (!isOperatorLike(head.text())) {
			return Style.data();
		}
		String key = operatorKey(head.text());
		Style rule = RULES.get(key);
		return rule != null ? rule : byNamingConvention(key, items);
	}

	/**
	 * The style of an operator the table does not know, guessed from its name.
	 * <p>
	 * This is not a nicety. Getting it wrong is not "slightly different indentation": a
	 * body-taking macro laid out as a call aligns its whole body under its first
	 * argument, so one {@code usocket:with-server-socket} pushes everything inside it
	 * thirty columns right and every line of it past the margin. Lisp's naming
	 * conventions are strong enough to read -- Emacs' own {@code lisp-indent-function}
	 * has always treated any {@code def}-prefixed symbol as a definition for exactly this
	 * reason -- and a macro that follows none of them is no worse off than before.
	 * @param key the operator name, lowercased and stripped of its package prefix
	 * @param items the form's items, with the operator at index 0
	 * @return the guessed style
	 */
	private static Style byNamingConvention(String key, List<CstNode> items) {
		// with-FOO (spec) body..., do-FOO (spec) body...
		if (key.startsWith("with-") || key.startsWith("do-") || key.startsWith("dolist-")) {
			return Style.body(1, 2);
		}
		// without-FOO body...
		if (key.startsWith("without-")) {
			return Style.body(0, 2);
		}
		// defFOO name lambda-list body... when the form is written that way,
		// defFOO name body... otherwise. Nothing but the form's own contents tells
		// the two apart, so the number of distinguished arguments is READ OFF it
		// rather than fixed: at one, a lambda list is stranded on a line of its own
		// (a header plus a body of two is two statements, and two statements never
		// share a line); at two, a body form is pulled up beside the operator and
		// the rest of the body aligns under it.
		if (key.startsWith("def") && key.length() > 3) {
			return isDefunShaped(items) ? Style.body(2, 2) : Style.body(1, 2);
		}
		return Style.call();
	}

	/**
	 * Whether the form may be laid out the way {@code defun} is: a NAME, then a LAMBDA
	 * LIST, then a body. A body has to follow, or there is no reason to prefer either
	 * reading and no line to be saved by preferring one.
	 * @param items the form's items, with the operator at index 0
	 * @return {@code true} if the form has a name and a lambda list
	 */
	private static boolean isDefunShaped(List<CstNode> items) {
		return items.size() > 3 && items.get(1) instanceof CstNode.Atom && isLambdaList(items.get(2));
	}

	/**
	 * Whether a node could be a lambda list rather than a form. Nothing SAYS which it is
	 * -- {@code (req)} is equally a one-parameter lambda list and a call of no arguments
	 * -- so the question is asked the only way it can be answered: every element has to
	 * be something a lambda list is allowed to hold. A string, a number, a keyword or a
	 * nested form settles it the other way, and so, decisively, do {@code nil} and
	 * {@code t} -- a lambda list may not bind a constant, which is what tells
	 * alexandria's {@code (deftest xor.3 (xor nil nil nil) nil t)} from a definition
	 * written in the same bare symbols. The empty list is the one case that needs no
	 * reasoning at all: a call with no operator does not exist.
	 * <p>
	 * The remaining ambiguity is real and is decided in favour of the lambda list: a
	 * {@code (deftest NAME (foo bar) ...)} whose second element is a two-symbol CALL is
	 * laid out as though it were a header. Over every {@code .lisp} and {@code .asd} in
	 * the repository, all 90 forms the rule re-laid out are genuine definitions -- but
	 * the reading is a guess, not a deduction, and an operator that matters belongs in
	 * {@link #RULES}.
	 * @param node the node
	 * @return {@code true} if the node could be a lambda list
	 */
	private static boolean isLambdaList(CstNode node) {
		if (!(node instanceof CstNode.Listing listing) || !"(".equals(listing.open())) {
			return false;
		}
		for (CstNode item : listing.items()) {
			if (!(item instanceof CstNode.Atom atom) || !isVariableName(atom.text())) {
				return false;
			}
		}
		return true;
	}

	// A token that can name a lambda-list parameter: a plain symbol, or an &-marker. A
	// keyword, a string, a number, a character, a #-literal or one of the two constants
	// nil and t cannot be one.
	private static boolean isVariableName(String text) {
		char first = text.charAt(0);
		if (first == ':' || first == '"' || first == '#' || first == '.' || isDigit(first)) {
			return false;
		}
		if ((first == '+' || first == '-') && text.length() > 1 && isDigit(text.charAt(1))) {
			return false;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		return !"nil".equals(lower) && !"t".equals(lower);
	}

	/**
	 * The style forced onto the child at the given index by its parent's style, or
	 * {@code null} when the child picks its own.
	 * @param style the parent's style
	 * @param index the child's index among the listing's items
	 * @return the forced style, or {@code null}
	 */
	@Nullable public static Style childStyle(Style style, int index) {
		return switch (style.kind()) {
			case DATA -> style.childStyle();
			case CALL -> index >= 1 ? style.childStyle() : null;
			case BODY -> index == 1 ? style.childStyle() : null;
			case CLAUSES -> index > style.inlineArgs() ? style.childStyle() : null;
			default -> null;
		};
	}

	// A head that could name an operator. A number, string, character or '#' literal in
	// the head position means the list is data -- (1 2 3) is not a call to 1.
	private static boolean isOperatorLike(String text) {
		char first = text.charAt(0);
		if (first == '"' || first == '#' || isDigit(first)) {
			return false;
		}
		if ((first == '+' || first == '-' || first == '.') && text.length() > 1 && isDigit(text.charAt(1))) {
			return false;
		}
		return !".".equals(text);
	}

	/**
	 * The table key for an operator as written. Case is irrelevant (the reader upcases),
	 * and a package prefix is stripped so {@code cl:when} and
	 * {@code rontolisp:with-mutex} find their rule. A name that STARTS with a colon is
	 * left alone: {@code :if} is a plist key, not {@code if}.
	 */
	private static String operatorKey(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		if (lower.startsWith(":")) {
			return lower;
		}
		int colon = lower.lastIndexOf(':');
		return colon < 0 ? lower : lower.substring(colon + 1);
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

}
