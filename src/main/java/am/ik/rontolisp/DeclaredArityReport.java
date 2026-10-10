package am.ik.rontolisp;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The wrong-argument-count report a function DECLARES for itself:
 * {@code (declare (%arity-report prefix suffix))} at the head of its body makes a call
 * with a count its lambda list rules out report {@code prefix + count + suffix} -- still
 * a {@code program-error} -- where any other program function reports
 * {@code Function expects N argument(s), got M} ({@link ClosRegistry#arityMessage}).
 *
 * <p>
 * The text is spelled by the front end that wrote the function (Clojure's
 * {@code Wrong number of args (0) passed to: my.app/one}), so no backend learns a
 * language's wording: each reads the declaration where it already judges a count -- the
 * interpreter's lambda application, a compiled direct call, a dispatcher's no-match arm,
 * the count guard of a spread case or a literal {@code apply} -- on the refusal path
 * only, so a call with the right count costs nothing.
 *
 * @param prefix the text before the count
 * @param suffix the text after it
 */
public record DeclaredArityReport(String prefix, String suffix) {

	/**
	 * {@return the report of a call that passed {@code got} arguments}
	 * @param got the argument count
	 */
	public String message(int got) {
		return this.prefix + got + this.suffix;
	}

	/**
	 * The declaration that makes a function report this way, to put at the head of its
	 * body.
	 * @return {@code (declare (%arity-report prefix suffix))}
	 */
	public LispVal declaration() {
		LispVal spec = new LispCons(new LispSymbol(LispNames.ARITY_REPORT_INTERNAL), new LispCons(
				LispString.literal(this.prefix), new LispCons(LispString.literal(this.suffix), LispNil.INSTANCE)));
		return new LispCons(new LispSymbol(LispNames.DECLARE), new LispCons(spec, LispNil.INSTANCE));
	}

	/**
	 * The report a function body declares: an {@code %arity-report} specifier in a
	 * {@code declare} among the body's leading forms -- past a docstring, and inside the
	 * {@code block} a {@code defun} body is wrapped in on its way to a backend.
	 * @param body the function's body forms
	 * @return the declared report, or {@code null} when the body declares none
	 */
	public static @Nullable DeclaredArityReport of(List<LispVal> body) {
		for (int i = 0; i < body.size(); i++) {
			LispVal form = body.get(i);
			if (form instanceof LispString && i + 1 < body.size()) {
				continue; // a docstring
			}
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
				return null;
			}
			if (LispNames.DECLARE.equals(head.name())) {
				DeclaredArityReport found = ofDeclaration(cons);
				if (found != null) {
					return found;
				}
				continue;
			}
			if (body.size() == 1
					&& (LispNames.BLOCK.equals(head.name()) || LispNames.FN_BLOCK_INTERNAL.equals(head.name()))
					&& cons.cdr() instanceof LispCons named) {
				return of(listOf(named.cdr()));
			}
			return null;
		}
		return null;
	}

	/**
	 * The report a {@code lambda} form declares.
	 * @param lambda the {@code (lambda params . body)} form
	 * @return the declared report, or {@code null}
	 */
	public static @Nullable DeclaredArityReport ofLambda(LispCons lambda) {
		return lambda.cdr() instanceof LispCons params ? of(listOf(params.cdr())) : null;
	}

	private static @Nullable DeclaredArityReport ofDeclaration(LispCons declare) {
		for (LispVal specs = declare.cdr(); specs instanceof LispCons cell; specs = cell.cdr()) {
			if (cell.car() instanceof LispCons spec && spec.car() instanceof LispSymbol id && isIdentifier(id.name())
					&& spec.cdr() instanceof LispCons first && first.car() instanceof LispString prefix
					&& first.cdr() instanceof LispCons second && second.car() instanceof LispString suffix
					&& second.cdr() instanceof LispNil) {
				return new DeclaredArityReport(prefix.value(), suffix.value());
			}
		}
		return null;
	}

	private static boolean isIdentifier(String name) {
		return LispNames.ARITY_REPORT_INTERNAL.equals(name)
				|| ("RONTOLISP::" + LispNames.ARITY_REPORT_INTERNAL).equals(name);
	}

	private static List<LispVal> listOf(LispVal list) {
		List<LispVal> out = new java.util.ArrayList<>();
		for (LispVal l = list; l instanceof LispCons cell; l = cell.cdr()) {
			out.add(cell.car());
		}
		return out;
	}

}
