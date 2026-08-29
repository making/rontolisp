package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.SourceProvenance;

import org.jspecify.annotations.Nullable;

/**
 * Moves a top-level {@code defun} out of the compile-time function namespace when a
 * {@code defun} somewhere else in the program REDEFINES the same name at run time.
 * <p>
 * Both compile backends resolve a call by NAME against the map Pass 1 builds, and a
 * non-top-level {@code defun} is not in that map: it lowers to
 * {@code (setq name (lambda ...))} and its call sites dispatch through the global
 * VARIABLE instead ({@code LispMacroExpander.expandCallThroughVariable}). When the same
 * name also has a top-level {@code defun}, every call site found the compiled function
 * first and the nested definition was written to a store nothing read -- so
 * {@code (defun over () 'top)} plus a nested {@code (defun over () 'nested)} answered
 * {@code TOP} after the redefinition where the interpreter and SBCL answer
 * {@code NESTED}.
 * <p>
 * The fix is to give those names ONE resolution rule instead of two. For a name that has
 * both spellings the top-level {@code defun} is renamed to an internal name and an
 * assignment of its function value is left behind in its place:
 *
 * <pre>{@code
 * (defun over () 'top)          ->  (defun %top-defun$over () 'top)
 *                                   (setq over (function %top-defun$over))
 * }</pre>
 *
 * The name is then a global variable and nothing else, so every mechanism that already
 * serves a nested {@code defun} serves it too -- a call dispatches through the variable,
 * {@code #'over} reads it, and the last assignment executed wins, which is the
 * interpreter's answer. The assignment sits where the {@code defun} sat, so a top-level
 * call BEFORE it is as undefined as it is in the interpreter.
 * <p>
 * A program with no such name is returned unchanged, so the rename costs an indirect call
 * only where the two spellings actually meet.
 */
public final class NestedDefunRedefinition {

	/**
	 * Prefix of the internal name a redefined top-level {@code defun} is compiled under.
	 * {@code %}-prefixed by the internal-helper convention; the {@code $} keeps it out of
	 * reach of a name a program could spell for itself.
	 */
	private static final String INTERNAL_PREFIX = "%top-defun$";

	private NestedDefunRedefinition() {
	}

	/**
	 * Rewrites the program so a top-level {@code defun} whose name is redefined by a
	 * non-top-level {@code defun} resolves through its global variable.
	 * @param program the whole program, after every pass that can introduce a top-level
	 * defun of its own
	 * @return the rewritten program, or the same list when no name has both spellings
	 * @throws UnsupportedOperationException when such a name is ALSO declared a global
	 * variable by a top-level {@code defvar}/{@code defparameter}/{@code defconstant} --
	 * the function value and the variable's value would have to share one cell
	 */
	public static List<LispVal> rewrite(List<LispVal> program) {
		Set<String> nested = GlobalVarCollector.collectAllNestedDefunNames(program);
		if (nested.isEmpty()) {
			return program;
		}
		LinkedHashSet<String> redefined = new LinkedHashSet<>();
		for (LispVal expr : program) {
			String name = topLevelDefunName(expr);
			if (name != null && nested.contains(name)) {
				redefined.add(name);
			}
		}
		if (redefined.isEmpty()) {
			return program;
		}
		rejectVariableCollision(program, redefined);
		rejectExportCollision(program, redefined);
		List<LispVal> rewritten = new ArrayList<>(program.size() + redefined.size());
		for (LispVal expr : program) {
			String name = topLevelDefunName(expr);
			if (name == null || !redefined.contains(name)) {
				rewritten.add(expr);
				continue;
			}
			LispCons defun = (LispCons) expr;
			LispCons nameCell = (LispCons) defun.cdr();
			String internal = INTERNAL_PREFIX + name;
			rewritten.add(SourceProvenance.inherit(defun,
					new LispCons(defun.car(), new LispCons(new LispSymbol(internal), nameCell.cdr()))));
			rewritten.add(SourceProvenance.inherit(defun, assignFunctionValue(name, internal)));
		}
		return rewritten;
	}

	/**
	 * A name whose function value now lives in a global variable AND whose variable value
	 * a top-level declaration owns has nowhere to put the second one: the backends give a
	 * name one cell (Lisp-2 is a compile-time distinction here, not a runtime one). It
	 * used to be silently wrong in the other direction -- the redefinition was simply not
	 * observed -- so say what is unsupported instead of picking one of the two values.
	 */
	private static void rejectVariableCollision(List<LispVal> program, Set<String> redefined) {
		for (LispVal expr : program) {
			if (!(expr instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)
					|| !(cons.cdr() instanceof LispCons nameCell) || !(nameCell.car() instanceof LispSymbol name)) {
				continue;
			}
			switch (head.name()) {
				case LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> {
					if (redefined.contains(name.name())) {
						throw new UnsupportedOperationException(SourceProvenance.prefix(cons) + "the function "
								+ name.name() + " is redefined by a nested defun, so its function value is held by "
								+ "the global variable " + name.name() + " -- which " + head.name()
								+ " also declares. Rename one of the two.");
					}
				}
				default -> {
				}
			}
		}
	}

	/**
	 * An exported name binds ONE static definition -- the host calls the typed wrapper
	 * beside the defun method, and there is no defun method once the name resolves
	 * through a variable. Left alone the export directive would fail with "names an
	 * unknown function (must be a top-level defun)" about a name that IS one, which is
	 * exactly the kind of misdirection this item was closing.
	 */
	private static void rejectExportCollision(List<LispVal> program, Set<String> redefined) {
		for (LispVal expr : program) {
			if (!(expr instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)
					|| !(cons.cdr() instanceof LispCons argCell)) {
				continue;
			}
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(head.name());
			if (qn == null || !LispNames.RONTOLISP_PKG.equals(qn.pkg())
					|| !(LispNames.JVM_EXPORT.equals(qn.member()) || LispNames.WASM_EXPORT.equals(qn.member()))) {
				continue;
			}
			if (argCell.car() instanceof LispCons quoted && quoted.car() instanceof LispSymbol quote
					&& LispNames.QUOTE.equals(quote.name()) && quoted.cdr() instanceof LispCons nameCell
					&& nameCell.car() instanceof LispSymbol name && redefined.contains(name.name())) {
				throw new UnsupportedOperationException(SourceProvenance.prefix(cons) + "the function " + name.name()
						+ " is exported (" + head.name() + ") and also redefined by a nested defun. An export binds "
						+ "one static definition, which a redefinable name does not have. Rename one of the two.");
			}
		}
	}

	/** {@code (setq name (function internal))}. */
	private static LispCons assignFunctionValue(String name, String internal) {
		LispVal functionForm = new LispCons(new LispSymbol(LispNames.FUNCTION),
				new LispCons(new LispSymbol(internal), LispNil.INSTANCE));
		return new LispCons(new LispSymbol(LispNames.SETQ),
				new LispCons(new LispSymbol(name), new LispCons(functionForm, LispNil.INSTANCE)));
	}

	/**
	 * The name of a top-level {@code (defun name ...)}, or {@code null} when the form is
	 * not one (a {@code #'(setf place)} writer's name is a cons, not a symbol, and stays
	 * out of this).
	 */
	private static @Nullable String topLevelDefunName(LispVal expr) {
		if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.DEFUN.equals(head.name()) && cons.cdr() instanceof LispCons nameCell
				&& nameCell.car() instanceof LispSymbol name && nameCell.cdr() instanceof LispCons) {
			return name.name();
		}
		return null;
	}

}
