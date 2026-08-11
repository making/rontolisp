package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

/**
 * A top-level form is compiled in STATEMENT position: {@code _start} (wasm) and
 * {@code main} (JVM) drop every form's value, so a value a top-level form produces purely
 * in order to have something to return is code no run can observe.
 *
 * <p>
 * Two shapes produce exactly that, and this class is where both are recognized, once, for
 * both compile paths:
 *
 * <ul>
 * <li>{@link #prune(List) a form that IS a constant} -- {@code (in-package :chipz)} and
 * {@code (defpackage ...)} resolve at compile time to {@code 'CHIPZ}
 * ({@code PackageResolver}), a {@code declaim} / an unselected {@code eval-when} leaves
 * {@code nil}, and a stray docstring is a bare string. Nothing is left to run, so the
 * whole form is dropped from the top-level list rather than emitted and popped.</li>
 * <li>{@link #isNameValuedDefiner(LispVal) a definer whose value is its own name} --
 * {@code defvar}/{@code defparameter}/{@code defconstant} bind the variable and then
 * return the name symbol. The binding is the effect; the symbol is not. The two backends'
 * top-level emitters compile these for effect, so the name is never materialized.</li>
 * </ul>
 *
 * <p>
 * <b>Why this is not behind {@code --optimize}.</b> Neither shape is a speed/size trade
 * with a losing side: the removed code cannot be observed at any level, and leaving it in
 * makes the flag-less module differ from the shaken one for no reason. It is the same
 * standing this project gives {@code PureBuiltinFolder}
 * ({@code .kb/pure-builtin-fold.md}) -- always on, both compile paths.
 *
 * <p>
 * <b>Why only constants are pruned.</b> A bare non-keyword symbol at top level is NOT
 * effect-free: evaluating it can signal an unbound-variable error, which is an observable
 * effect, so it stays. Neither is a call to a function whose body happens to be pure --
 * deciding that is {@link am.ik.rontolisp.macro.PureBuiltinFolder}'s job, and anything it
 * folds to a literal arrives here already a constant and is pruned by the first rule.
 *
 * <p>
 * Scope is the TOP-LEVEL list only. The same reasoning holds for a non-final statement of
 * a {@code progn}/{@code let} body, but a constant in that position is written by a human
 * rather than left behind by a resolver, so there is nothing there to collect;
 * measurements and the pinning test live at the top level
 * ({@code .kb/toplevel-statement-values.md}).
 *
 * @see am.ik.rontolisp.macro.PureBuiltinFolder
 */
public final class ToplevelStatements {

	private ToplevelStatements() {
	}

	/**
	 * Drops the top-level forms whose evaluation produces a constant and does nothing
	 * else. Order and identity of every surviving form are untouched, so the
	 * cons-identity rule source positions depend on ({@code .kb/source-positions.md})
	 * holds trivially: this pass only deletes.
	 * @param topLevelForms the top-level forms about to be emitted
	 * @return the forms that still have something to do, in the same order
	 */
	public static List<LispVal> prune(List<LispVal> topLevelForms) {
		List<LispVal> kept = new ArrayList<>(topLevelForms.size());
		for (LispVal form : topLevelForms) {
			if (!isConstant(form)) {
				kept.add(form);
			}
		}
		return kept;
	}

	/**
	 * Whether the form evaluates to a constant with no other effect: a self-evaluating
	 * literal, {@code nil}/{@code t}, a keyword, or a {@code (quote ...)} of anything.
	 * @param form a top-level form
	 * @return {@code true} when emitting the form could only push a value nobody reads
	 */
	public static boolean isConstant(LispVal form) {
		return switch (form) {
			case LispNil ignored -> true;
			case LispTrue ignored -> true;
			case LispInteger ignored -> true;
			case LispBigInteger ignored -> true;
			case LispRatio ignored -> true;
			case LispDouble ignored -> true;
			case LispString ignored -> true;
			case LispChar ignored -> true;
			// A keyword evaluates to itself; any other symbol may be unbound, and
			// signalling that is an effect.
			case LispSymbol sym -> sym.isKeyword();
			case LispCons cons -> cons.car() instanceof LispSymbol op && LispNames.QUOTE.equals(member(op.name()));
			default -> false;
		};
	}

	/**
	 * Whether the form is one of the three definers whose returned value is nothing but
	 * the name symbol it just bound, so a top-level emitter can compile it for effect and
	 * emit no value at all.
	 * @param form a top-level form
	 * @return {@code true} for {@code (defvar NAME ...)}, {@code (defparameter NAME ...)}
	 * and {@code (defconstant NAME ...)}
	 */
	public static boolean isNameValuedDefiner(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)) {
			return false;
		}
		String name = member(op.name());
		if (!LispNames.DEFVAR.equals(name) && !LispNames.DEFPARAMETER.equals(name)
				&& !LispNames.DEFCONSTANT.equals(name)) {
			return false;
		}
		// The compilers cast the second element to a symbol; a malformed form must reach
		// them so the error is theirs, not a silent skip here.
		return cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol;
	}

	/**
	 * Whether a {@link #isNameValuedDefiner(LispVal) name-valued definer} rebinds
	 * unconditionally ({@code defparameter}/{@code defconstant}) rather than only when
	 * unbound ({@code defvar}) -- the {@code force} flag both backends' defvar compilers
	 * take.
	 * @param form a form {@link #isNameValuedDefiner(LispVal)} accepted
	 * @return {@code true} for {@code defparameter} and {@code defconstant}
	 */
	public static boolean definerRebinds(LispVal form) {
		LispSymbol op = (LispSymbol) ((LispCons) form).car();
		return !LispNames.DEFVAR.equals(member(op.name()));
	}

	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

}
