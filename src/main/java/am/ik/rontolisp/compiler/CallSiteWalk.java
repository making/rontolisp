package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrees;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * The one walk every call-site rewrite over surface forms takes: it offers the rewriter
 * each list in a CODE position and keeps the positions that are not code -- a binding's
 * variable, a lambda list, a {@code case} key, a {@code handler-case} clause's type and
 * variable, a slot specification, a declaration, a type, a {@code loop} destructuring
 * pattern, quoted data. A lexical variable may be named like a function ({@code close},
 * {@code listen}, {@code read-line}: CLHS 11.1.2.1.2.1 allows binding any {@code cl}
 * symbol that is not a variable), so {@code (let ((close s)) ...)} holds a binding, not a
 * call of {@code close}, and {@code (case k (close 1))} a key.
 *
 * <p>
 * The walk runs on the forms the CLI front end hands the backends, where the built-in
 * macros are still unexpanded, so it knows their shapes too. It keeps cons identity
 * wherever nothing changed ({@link LispCons#rebuiltList}), so a source position below an
 * untouched form survives.
 */
public final class CallSiteWalk {

	private CallSiteWalk() {
	}

	/** A call-site rewrite: what to make of each list in a code position. */
	public interface Rewriter {

		/**
		 * The replacement of a proper list in a code position headed by a symbol, or null
		 * to let the walk take it apart -- by its shape for a binding or definition form,
		 * element by element for anything else.
		 * @param form the list
		 * @param head its head
		 * @return the replacement, or null
		 */
		@Nullable LispVal call(LispCons form, LispSymbol head);

		/**
		 * The rewriter of a function body nested in the form being walked.
		 * @param definer the defining operator's standard name ({@code DEFUN},
		 * {@code LAMBDA}, {@code FLET}, ... or a {@code rontolisp:} async definer)
		 * @return the rewriter of that body
		 */
		default Rewriter body(String definer) {
			return this;
		}

		/**
		 * Sees a symbol in a code position: a variable reference (or a constant). A
		 * call's operator, a binding's variable and a place the walk takes apart are not
		 * code positions.
		 * @param symbol the symbol
		 */
		default void variable(LispSymbol symbol) {
		}

	}

	/**
	 * Walks one form.
	 * @param form the form, in a code position
	 * @param rewriter the rewrite
	 * @return the rewritten form, or {@code form} itself when nothing changed
	 */
	public static LispVal walk(LispVal form, Rewriter rewriter) {
		if (!(form instanceof LispCons cons)) {
			if (form instanceof LispSymbol symbol) {
				rewriter.variable(symbol);
			}
			return form;
		}
		if (cons.car() instanceof LispSymbol head && cons.isProperList()) {
			LispVal replaced = rewriter.call(cons, head);
			if (replaced != null) {
				return replaced;
			}
			LispVal shaped = byShape(cons, standardName(head), rewriter);
			if (shaped != null) {
				return shaped;
			}
		}
		LispVal operator = cons.car() instanceof LispSymbol ? cons.car() : walk(cons.car(), rewriter);
		return LispCons.rebuilt(cons, operator, arguments(cons.cdr(), rewriter));
	}

	/**
	 * Walks a call's arguments: each element of the list is a form. The tail is walked
	 * element-wise and never re-read as a call, so an argument sequence starting with an
	 * operator-named symbol -- {@code (error 'ty :format-control format)} -- stays data.
	 * @param tail the argument list
	 * @param rewriter the rewrite
	 * @return the rewritten list, or {@code tail} itself when nothing changed
	 */
	public static LispVal arguments(LispVal tail, Rewriter rewriter) {
		return LispTrees.rebuildSpine(tail, node -> node instanceof LispCons ? null : node,
				element -> walk(element, rewriter));
	}

	/**
	 * Walks a {@code (lambda lambda-list body...)} form with its body under the given
	 * rewriter, for a rewriter that knows the body's context better than its own
	 * {@link Rewriter#body} does (an asynchronous thunk).
	 * @param lambda the lambda form
	 * @param body the rewriter of the lambda list's default forms and the body
	 * @return the rewritten form
	 */
	public static LispVal lambda(LispCons lambda, Rewriter body) {
		return function(lambda, lambda.toList(), 1, body);
	}

	// A CL symbol's spelling, unqualified, or the name as it stands: a front end that
	// runs before package resolution still sees the explicit cl:let a program wrote.
	private static String standardName(LispSymbol head) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(head.name());
		return qn != null && (LispNames.CL_PKG.equals(qn.pkg()) || "COMMON-LISP".equals(qn.pkg())) ? qn.member()
				: head.name();
	}

	private static @Nullable LispVal byShape(LispCons cons, String op, Rewriter rewriter) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			return null;
		}
		return switch (op) {
			case LispNames.QUOTE, LispNames.UNSPELLED_QUOTE, LispNames.DEFMACRO, LispNames.DECLARE -> cons;
			case LispNames.MACROLET -> keepThenWalk(cons, parts, 2, rewriter);
			case LispNames.DEFUN, LispNames.ASYNC_DEFUN_QUALIFIED -> function(cons, parts, 2, rewriter.body(op));
			case LispNames.LAMBDA, LispNames.ASYNC_LAMBDA_QUALIFIED -> function(cons, parts, 1, rewriter.body(op));
			case LispNames.DEFMETHOD -> function(cons, parts, lambdaListAfterQualifiers(parts, 2), rewriter.body(op));
			case LispNames.FLET, LispNames.LABELS -> {
				List<LispVal> out = new ArrayList<>(parts);
				out.set(1, mapList(parts.get(1), def -> def instanceof LispCons defCons && defCons.isProperList()
						? function(defCons, defCons.toList(), 1, rewriter.body(op)) : def));
				walkFrom(out, parts, 2, rewriter);
				yield LispCons.rebuiltList(cons, out);
			}
			case LispNames.LET, LispNames.LET_STAR, LispNames.PROG, LispNames.PROG_STAR, LispNames.SYMBOL_MACROLET,
					LispNames.HANDLER_BIND, LispNames.RESTART_BIND ->
				withBindings(cons, parts, rewriter, -1);
			case LispNames.DO, LispNames.DO_STAR -> withBindings(cons, parts, rewriter, 2);
			case LispNames.DOLIST, LispNames.DOTIMES, LispNames.DO_SYMBOLS, LispNames.DO_EXTERNAL_SYMBOLS,
					LispNames.DO_ALL_SYMBOLS, LispNames.WITH_OPEN_FILE, LispNames.WITH_OPEN_STREAM,
					LispNames.WITH_OUTPUT_TO_STRING, LispNames.WITH_INPUT_FROM_STRING -> {
				List<LispVal> out = new ArrayList<>(parts);
				out.set(1, clause(parts.get(1), 1, rewriter));
				walkFrom(out, parts, 2, rewriter);
				yield LispCons.rebuiltList(cons, out);
			}
			case LispNames.MULTIPLE_VALUE_BIND, LispNames.MULTIPLE_VALUE_SETQ, LispNames.DESTRUCTURING_BIND,
					LispNames.WITH_SLOTS, LispNames.WITH_ACCESSORS, LispNames.THE ->
				keepThenWalk(cons, parts, 2, rewriter);
			case LispNames.CASE, LispNames.ECASE, LispNames.CCASE, LispNames.TYPECASE, LispNames.ETYPECASE,
					LispNames.CTYPECASE ->
				clauses(cons, parts, 1, rewriter);
			case LispNames.HANDLER_CASE, LispNames.RESTART_CASE -> clauses(cons, parts, 2, rewriter);
			case LispNames.COND -> clauses(cons, parts, 0, rewriter);
			case LispNames.SETF, LispNames.PSETF, LispNames.INCF, LispNames.DECF, LispNames.POP, LispNames.REMF,
					LispNames.PUSH, LispNames.PUSHNEW, LispNames.ROTATEF, LispNames.SHIFTF -> {
				List<LispVal> out = new ArrayList<>(parts.size());
				out.add(parts.get(0));
				for (int i = 1; i < parts.size(); i++) {
					out.add(isPlacePosition(op, i, parts.size()) ? place(parts.get(i), rewriter)
							: walk(parts.get(i), rewriter));
				}
				yield LispCons.rebuiltList(cons, out);
			}
			case LispNames.DEFCLASS, LispNames.DEFINE_CONDITION -> {
				if (parts.size() < 4) {
					yield cons;
				}
				List<LispVal> out = new ArrayList<>(parts);
				out.set(3, mapList(parts.get(3), slot -> slot(slot, 1, rewriter)));
				walkFrom(out, parts, 4, rewriter);
				yield LispCons.rebuiltList(cons, out);
			}
			case LispNames.DEFSTRUCT -> {
				List<LispVal> out = new ArrayList<>(parts);
				int slotsFrom = parts.size() > 2 && parts.get(2) instanceof LispString ? 3 : 2;
				for (int i = slotsFrom; i < parts.size(); i++) {
					out.set(i, slot(parts.get(i), 2, rewriter));
				}
				yield LispCons.rebuiltList(cons, out);
			}
			case LispNames.DEFGENERIC -> {
				// (defgeneric name lambda-list option...): an option is data but a
				// (:method qualifier* lambda-list body...), which is a method.
				List<LispVal> out = new ArrayList<>(parts);
				for (int i = 3; i < parts.size(); i++) {
					if (parts.get(i) instanceof LispCons option && option.isProperList()
							&& option.car() instanceof LispSymbol key && ":METHOD".equals(key.name())) {
						List<LispVal> method = option.toList();
						out.set(i, function(option, method, lambdaListAfterQualifiers(method, 1),
								rewriter.body(LispNames.DEFMETHOD)));
					}
				}
				yield LispCons.rebuiltList(cons, out);
			}
			case LispNames.LOOP -> {
				// A destructuring pattern after for / as / with is a tree of variables;
				// loop keywords are compared by name, whatever their package.
				List<LispVal> out = new ArrayList<>(parts.size());
				out.add(parts.get(0));
				for (int i = 1; i < parts.size(); i++) {
					boolean pattern = parts.get(i) instanceof LispCons && parts.get(i - 1) instanceof LispSymbol key
							&& isLoopBindingKeyword(key);
					out.add(pattern ? parts.get(i) : walk(parts.get(i), rewriter));
				}
				yield LispCons.rebuiltList(cons, out);
			}
			default -> null;
		};
	}

	private static boolean isLoopBindingKeyword(LispSymbol key) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(key.name());
		String name = qn == null ? key.name() : qn.member();
		return "FOR".equals(name) || "AS".equals(name) || "WITH".equals(name);
	}

	// (definer [name] lambda-list body...) with the lambda list at lambdaListAt: the
	// lambda list's default forms and the body are walked under the body's rewriter.
	private static LispVal function(LispCons cons, List<LispVal> parts, int lambdaListAt, Rewriter body) {
		if (lambdaListAt >= parts.size()) {
			return cons;
		}
		List<LispVal> out = new ArrayList<>(parts);
		out.set(lambdaListAt, mapList(parts.get(lambdaListAt), param -> clause(param, 1, body)));
		walkFrom(out, parts, lambdaListAt + 1, body);
		return LispCons.rebuiltList(cons, out);
	}

	// A defmethod's lambda list follows its qualifiers (symbols), which follow its name.
	private static int lambdaListAfterQualifiers(List<LispVal> parts, int from) {
		int i = from;
		while (i < parts.size() && parts.get(i) instanceof LispSymbol) {
			i++;
		}
		return i;
	}

	// (op (binding...) form...): a binding's variable stays, its value forms evaluate.
	// clauseAt, when not -1, is a following list every element of which is a form (do's
	// end-test clause).
	private static LispVal withBindings(LispCons cons, List<LispVal> parts, Rewriter rewriter, int clauseAt) {
		if (parts.size() < 2) {
			return cons;
		}
		List<LispVal> out = new ArrayList<>(parts);
		out.set(1, mapList(parts.get(1), binding -> clause(binding, 1, rewriter)));
		for (int i = 2; i < parts.size(); i++) {
			out.set(i, i == clauseAt ? clause(parts.get(i), 0, rewriter) : walk(parts.get(i), rewriter));
		}
		return LispCons.rebuiltList(cons, out);
	}

	// (op form clause...) with each clause's first keep elements data.
	private static LispVal clauses(LispCons cons, List<LispVal> parts, int keep, Rewriter rewriter) {
		List<LispVal> out = new ArrayList<>(parts);
		int from = keep == 0 ? 1 : 2;
		if (keep > 0 && parts.size() > 1) {
			out.set(1, walk(parts.get(1), rewriter));
		}
		for (int i = from; i < parts.size(); i++) {
			out.set(i, clause(parts.get(i), keep, rewriter));
		}
		return LispCons.rebuiltList(cons, out);
	}

	// A list whose first keep elements are data and whose rest are forms; anything that
	// is not a proper list is data.
	private static LispVal clause(LispVal clause, int keep, Rewriter rewriter) {
		if (!(clause instanceof LispCons cons) || !cons.isProperList()) {
			return clause;
		}
		List<LispVal> parts = cons.toList();
		List<LispVal> out = new ArrayList<>(parts);
		walkFrom(out, parts, keep, rewriter);
		return LispCons.rebuiltList(cons, out);
	}

	// A slot specification: the name is data, positional elements up to optionsAt are
	// forms (defstruct's initform), and from there keywords alternate with value forms.
	private static LispVal slot(LispVal slot, int optionsAt, Rewriter rewriter) {
		if (!(slot instanceof LispCons cons) || !cons.isProperList()) {
			return slot;
		}
		List<LispVal> parts = cons.toList();
		List<LispVal> out = new ArrayList<>(parts);
		for (int i = 1; i < parts.size(); i++) {
			boolean keyword = i >= optionsAt && (i - optionsAt) % 2 == 0;
			out.set(i, keyword ? parts.get(i) : walk(parts.get(i), rewriter));
		}
		return LispCons.rebuiltList(cons, out);
	}

	// A place names its setf expansion, not a call: (setf (gethash k h) v) keeps the
	// head, and only the place's argument forms evaluate.
	private static LispVal place(LispVal place, Rewriter rewriter) {
		if (place instanceof LispCons cons && cons.car() instanceof LispSymbol && cons.isProperList()) {
			return LispCons.rebuilt(cons, cons.car(), arguments(cons.cdr(), rewriter));
		}
		return walk(place, rewriter);
	}

	/**
	 * Whether element {@code index} of a place-modifying form is a place: every other
	 * element from the first for {@code setf}/{@code psetf}, the second for
	 * {@code push}/{@code pushnew}, all but the new value for {@code shiftf}, every one
	 * for {@code rotatef}, and the first for the rest.
	 */
	private static boolean isPlacePosition(String op, int index, int size) {
		return switch (op) {
			case LispNames.SETF, LispNames.PSETF -> index % 2 == 1;
			case LispNames.PUSH, LispNames.PUSHNEW -> index == 2;
			case LispNames.ROTATEF -> true;
			case LispNames.SHIFTF -> index < size - 1;
			default -> index == 1;
		};
	}

	private static LispVal keepThenWalk(LispCons cons, List<LispVal> parts, int from, Rewriter rewriter) {
		List<LispVal> out = new ArrayList<>(parts);
		walkFrom(out, parts, from, rewriter);
		return LispCons.rebuiltList(cons, out);
	}

	private static void walkFrom(List<LispVal> out, List<LispVal> parts, int from, Rewriter rewriter) {
		for (int i = from; i < parts.size(); i++) {
			out.set(i, walk(parts.get(i), rewriter));
		}
	}

	// Each element of a proper list mapped; anything else is data.
	private static LispVal mapList(LispVal list, java.util.function.UnaryOperator<LispVal> element) {
		if (!(list instanceof LispCons cons) || !cons.isProperList()) {
			return list;
		}
		List<LispVal> out = new ArrayList<>();
		for (LispVal item : cons.toList()) {
			out.add(element.apply(item));
		}
		return LispCons.rebuiltList(cons, out);
	}

}
