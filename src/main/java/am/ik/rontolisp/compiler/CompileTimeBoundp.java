package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.SourceProvenance;
import org.jspecify.annotations.Nullable;

/**
 * {@code (boundp 'name)} over a LITERAL symbol is a compile-time fact, and this is where
 * both compile paths decide it.
 *
 * <p>
 * A compiled program is CLOSED: nothing can make a global spring into existence at run
 * time, so "is this name a global variable here?" is answered by the top-level forms
 * before this point -- exactly what {@link GlobalVarCollector} answers for the backing
 * stores, read in declaration order instead of as a whole-program set. The probe folds to
 * {@code t} or {@code nil} and the {@code boundp} disappears.
 *
 * <p>
 * <b>Why it is worth a pass.</b> {@code (unless (boundp '+k+) (defconstant +k+ v))} is
 * the portable redefinition-safe {@code defconstant} -- chipz, yason, iterate, mgl-pax,
 * global-vars and slime all spell it -- and it costs twice over. The probe sits in the
 * {@code usesEval} OR-chain of both backends, which emits the whole tree-walking
 * interpreter plus EVERY arity dispatcher ({@code .kb/eval-runtime.md}); and the guard
 * wrapping keeps the definition from being a top-level {@code defconstant}, so the AST
 * tree-shaker cannot drop it when nothing reads it. Folding the probe collapses the guard
 * to the definition the file means, which is what makes both go away. Which of the two a
 * given program pays -- on the zlib row it is the shaker half on both backends -- is
 * measured in {@code .kb/compile-time-boundp.md}.
 *
 * <p>
 * <b>Why it is not behind {@code --optimize}.</b> Neither direction is a trade: the probe
 * has one answer and the fold emits it. Same standing as {@link ToplevelStatements} and
 * {@link am.ik.rontolisp.macro.PureBuiltinFolder} -- always on, both compile paths.
 *
 * <p>
 * <b>The interpreter is deliberately untouched.</b> There the question is live (a REPL
 * form can define a global after the probe was read) and {@code boundp}'s answer and
 * error text are pinned.
 *
 * <p>
 * <b>The soundness gate is free.</b> The fold is only unsound when a global can appear at
 * run time: {@code eval}, {@code load}, or {@code --dynamic} late binding. Each of those
 * already forces the eval runtime on its own, so the condition that makes the fold
 * unsound is the same one that makes it pointless -- gating on it loses nothing.
 * ({@code set} and {@code (setf (symbol-value ...))} would belong here too; this language
 * has neither.)
 *
 * @see GlobalVarCollector
 */
public final class CompileTimeBoundp {

	private CompileTimeBoundp() {
	}

	/**
	 * Heads whose subforms do not run where they stand. A probe inside one cannot be
	 * decided from the top-level position it was WRITTEN at -- the body runs whenever it
	 * is called -- so it falls back to the whole-program question ("is this name ever a
	 * global?"), which has the same answer at every moment.
	 */
	private static final Set<String> DEFERRING = Set.of(LispNames.LAMBDA, LispNames.FUNCTION, LispNames.DEFUN,
			LispNames.DEFMACRO, LispNames.DEFMETHOD, LispNames.DEFGENERIC, LispNames.FLET, LispNames.LABELS,
			LispNames.MACROLET);

	/**
	 * Heads whose subforms can run more than once, so "the definer is written after the
	 * probe" no longer proves "the definer runs after the probe".
	 */
	private static final Set<String> REPEATING = Set.of(LispNames.DO, LispNames.DO_STAR, LispNames.DOTIMES,
			LispNames.DOLIST, LispNames.LOOP, LispNames.TAGBODY);

	/**
	 * Folds every compile-time-decidable {@code (boundp 'name)} in the program and
	 * collapses the top-level {@code if}/{@code when}/{@code unless} whose test the fold
	 * turned into a constant, splicing the surviving branch into the top-level list.
	 * @param program the top-level forms
	 * @param dynamic whether {@code --dynamic} late binding is on (any name can be
	 * resolved at run time, so nothing here is decidable)
	 * @param packagesResolved whether every symbol is spelled canonically
	 * ({@code PackageResolver} has run). Before that the same short name can belong to
	 * two packages, and a same-named definer elsewhere can only BLOCK a fold -- so the
	 * "unbound" direction still holds while the "bound" one, which would have to trust
	 * the match, is left for the pass the compilers run after resolution
	 * @return the folded program, the same list when nothing was decidable
	 */
	public static List<LispVal> fold(List<LispVal> program, boolean dynamic, boolean packagesResolved) {
		if (dynamic || callsAnywhere(program, LispNames.EVAL) || callsAnywhere(program, LispNames.LOAD)) {
			return program;
		}
		Names poisoned = new Names(packagesResolved);
		Names everBound = new Names(packagesResolved);
		for (LispVal form : program) {
			scan(form, false, true, everBound, poisoned);
		}
		Names boundBefore = new Names(packagesResolved);
		Set<String> definitelyBefore = new HashSet<>();
		List<LispVal> out = new ArrayList<>(program.size());
		boolean changed = false;
		for (LispVal form : program) {
			// The current form's own definers are read from the form as WRITTEN: a probe
			// they precede is undecidable whatever the fold does to the rest of it.
			Names here = new Names(packagesResolved);
			scan(form, false, true, here, new Names(packagesResolved));
			State state = new State(poisoned, everBound, boundBefore, definitelyBefore, here, packagesResolved);
			LispVal folded = rewrite(form, state, false, true);
			int before = out.size();
			emitReduced(folded, out);
			changed |= folded != form || out.size() - before != 1 || out.get(before) != form;
			// What the program actually runs from here on is what was EMITTED: a guard
			// the fold discharged as taken contributes its definitions, one it discharged
			// as skipped contributes none.
			for (int i = before; i < out.size(); i++) {
				scan(out.get(i), false, true, boundBefore, new Names(packagesResolved));
				unconditional(out.get(i), definitelyBefore);
			}
		}
		return changed ? out : program;
	}

	/**
	 * Whether the program calls {@code name} anywhere (head position, quoted data
	 * included).
	 */
	private static boolean callsAnywhere(List<LispVal> program, String name) {
		for (LispVal form : program) {
			if (calls(form, name)) {
				return true;
			}
		}
		return false;
	}

	private static boolean calls(LispVal form, String name) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol head && name.equals(member(head.name()))) {
			return true;
		}
		return calls(cons.car(), name) || calls(cons.cdr(), name);
	}

	// -- what a form makes a global ------------------------------------------------

	/**
	 * Records the names {@code form} makes global variables when it is evaluated, and the
	 * names whose boundness cannot be tracked at all.
	 * @param form the form to scan
	 * @param deferred whether the form sits inside a body that does not run where it
	 * stands -- an assignment there happens whenever the body is called, which is not an
	 * order this pass knows, so the name is poisoned rather than recorded
	 * @param topLevel whether the form IS a top-level form (a top-level {@code defun}
	 * defines a FUNCTION; a nested one is the closure-over-let idiom and compiles to an
	 * assignment of the name, exactly as {@link GlobalVarCollector} reads it)
	 * @param bound collects the names that become global variables
	 * @param poisoned collects the names this pass must never answer for
	 */
	private static void scan(LispVal form, boolean deferred, boolean topLevel, Names bound, Names poisoned) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		String op = cons.car() instanceof LispSymbol head ? member(head.name()) : null;
		if (LispNames.QUOTE.equals(op)) {
			return;
		}
		if (op != null) {
			List<LispVal> parts = cons.isProperList() ? cons.toList() : List.of();
			switch (op) {
				case LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> {
					if (parts.size() >= 2 && parts.get(1) instanceof LispSymbol name) {
						// (defvar x) with no value proclaims x special and binds
						// NOTHING, so a later (let ((x ...)) ...) is what makes boundp
						// answer t for its extent -- an order this pass does not model.
						boolean binds = !LispNames.DEFVAR.equals(op) || parts.size() >= 3;
						(binds && !deferred ? bound : poisoned).add(name.name());
					}
				}
				case LispNames.SETQ, LispNames.SETF -> {
					for (int i = 1; i + 1 < parts.size(); i += 2) {
						if (parts.get(i) instanceof LispSymbol place && !place.isKeyword()) {
							(deferred ? poisoned : bound).add(place.name());
						}
					}
				}
				case LispNames.DEFUN -> {
					if (!topLevel && cons.cdr() instanceof LispCons nameCell
							&& nameCell.car() instanceof LispSymbol name) {
						(deferred ? poisoned : bound).add(name.name());
					}
				}
				case LispNames.DECLAIM, LispNames.PROCLAIM, LispNames.DECLARE -> {
					// A special proclamation binds nothing by itself, but it is what
					// lets a let of the name bind it dynamically.
					collectSpecials(cons.cdr(), poisoned);
					return;
				}
				default -> {
				}
			}
		}
		boolean nowDeferred = deferred || (op != null && DEFERRING.contains(op));
		scan(cons.car(), nowDeferred, false, bound, poisoned);
		scan(cons.cdr(), nowDeferred, false, bound, poisoned);
	}

	/**
	 * Collects the names of every {@code (special ...)} declaration in the given tail.
	 */
	private static void collectSpecials(LispVal form, Names poisoned) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head && LispNames.SPECIAL.equals(member(head.name()))) {
			for (LispVal rest = cons.cdr(); rest instanceof LispCons cell; rest = cell.cdr()) {
				if (cell.car() instanceof LispSymbol name) {
					poisoned.add(name.name());
				}
			}
			return;
		}
		collectSpecials(cons.car(), poisoned);
		collectSpecials(cons.cdr(), poisoned);
	}

	/**
	 * Records the names a top-level form binds UNCONDITIONALLY -- the only ones the
	 * "bound" direction may answer t for. {@link #scan} deliberately over-collects (it is
	 * blind to lexical scope and to conditionals, like {@link GlobalVarCollector}), which
	 * is the safe direction for refusing a fold and the wrong one for asserting a
	 * binding: {@code (if (foo) (defvar *x* 1))} and {@code (let ((x 1)) (setq x 2))}
	 * both leave the global untouched.
	 */
	private static void unconditional(LispVal form, Set<String> out) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head) || !cons.isProperList()) {
			return;
		}
		List<LispVal> parts = cons.toList();
		switch (member(head.name())) {
			case LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> {
				if (parts.size() >= 3 && parts.get(1) instanceof LispSymbol name) {
					out.add(name.name());
				}
			}
			case LispNames.SETQ, LispNames.SETF -> {
				for (int i = 1; i + 1 < parts.size(); i += 2) {
					if (parts.get(i) instanceof LispSymbol place && !place.isKeyword()) {
						out.add(place.name());
					}
				}
			}
			case LispNames.PROGN -> {
				for (int i = 1; i < parts.size(); i++) {
					unconditional(parts.get(i), out);
				}
			}
			default -> {
			}
		}
	}

	// -- the rewrite ---------------------------------------------------------------

	/** What the walk of ONE top-level form knows while it decides that form's probes. */
	private static final class State {

		private final Names poisoned;

		private final Names everBound;

		private final Names boundBefore;

		private final Set<String> definitelyBefore;

		private final Names here;

		private final boolean packagesResolved;

		/**
		 * Whether the walk is still on the current top-level form's straight-line
		 * evaluation prefix: no definer has been passed and no deferred or repeating body
		 * has been entered. It is what makes the guard idiom decidable -- the probe of
		 * {@code (unless (boundp 'x) (defconstant x v))} runs BEFORE the definition two
		 * tokens later.
		 */
		private boolean prefixClean = true;

		private State(Names poisoned, Names everBound, Names boundBefore, Set<String> definitelyBefore, Names here,
				boolean packagesResolved) {
			this.poisoned = poisoned;
			this.everBound = everBound;
			this.boundBefore = boundBefore;
			this.definitelyBefore = definitelyBefore;
			this.here = here;
			this.packagesResolved = packagesResolved;
		}

	}

	private static LispVal rewrite(LispVal form, State state, boolean deferred, boolean topLevelForm) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		String op = cons.car() instanceof LispSymbol head ? member(head.name()) : null;
		if (LispNames.QUOTE.equals(op)) {
			return form;
		}
		if (LispNames.BOUNDP.equals(op) && cons.cdr() instanceof LispCons argCell && argCell.cdr() instanceof LispNil) {
			LispVal answer = decide(argCell.car(), state, deferred);
			if (answer != null) {
				return SourceProvenance.inherit(cons, answer);
			}
		}
		if (op != null && (DEFERRING.contains(op) || REPEATING.contains(op))) {
			state.prefixClean = false;
		}
		boolean nowDeferred = deferred || (op != null && DEFERRING.contains(op));
		// car before cdr is the head, then the arguments left to right: the source order
		// the prefix flag reads as evaluation order.
		LispVal car = rewrite(cons.car(), state, nowDeferred, false);
		LispVal cdr = rewrite(cons.cdr(), state, nowDeferred, false);
		if (op != null && (LispNames.DEFVAR.equals(op) || LispNames.DEFPARAMETER.equals(op)
				|| LispNames.DEFCONSTANT.equals(op) || LispNames.SETQ.equals(op) || LispNames.SETF.equals(op))) {
			state.prefixClean = false;
		}
		LispVal rebuilt = LispCons.rebuilt(cons, car, cdr);
		// The top-level form's own conditional is left for emitReduced, which splices the
		// surviving branch INTO the top-level list instead of wrapping it in a progn --
		// what makes the discharged guard's definition a top-level definer again.
		if (!topLevelForm && rebuilt != cons && rebuilt instanceof LispCons rewritten) {
			LispVal collapsed = collapseDecided(rewritten);
			if (collapsed != null) {
				return SourceProvenance.inherit(cons, collapsed);
			}
		}
		// Every cons on the path from the top-level form down to a folded probe is a
		// fresh
		// key in the identity-keyed provenance table, so each takes the position of the
		// one it stands for (.kb/source-positions.md).
		return SourceProvenance.inherit(cons, rebuilt);
	}

	/**
	 * Collapses a form whose deciding subform this pass just turned into {@code t} /
	 * {@code nil}, or null when there is nothing to collapse. Only reached for a cons the
	 * pass actually rewrote, so it never becomes a general constant folder -- but
	 * reaching it matters: the OTHER portable define-constant spelling is
	 * {@code (defconstant NAME (if (boundp 'NAME) (symbol-value 'NAME) v))} (cl-ppcre,
	 * cl-who, flexi-streams, cl-base64, cl-unicode all define it), and a decided probe
	 * that leaves the dead {@code (symbol-value 'NAME)} standing holds the eval runtime
	 * open by that arm instead -- 20,667 bytes against 4,734 on the two-form program.
	 */
	private static @Nullable LispVal collapseDecided(LispCons cons) {
		if (!(cons.car() instanceof LispSymbol head) || !cons.isProperList()) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			return null;
		}
		boolean decidedTrue = parts.get(1) instanceof LispTrue;
		if (!decidedTrue && !(parts.get(1) instanceof LispNil)) {
			return null;
		}
		return switch (member(head.name())) {
			// A malformed if is left whole so the error stays the compiler's.
			case LispNames.IF -> parts.size() != 3 && parts.size() != 4 ? null
					: decidedTrue ? parts.get(2) : parts.size() == 4 ? parts.get(3) : LispNil.INSTANCE;
			case LispNames.WHEN -> decidedTrue ? body(parts) : LispNil.INSTANCE;
			case LispNames.UNLESS -> decidedTrue ? LispNil.INSTANCE : body(parts);
			// Only the FIRST argument: a later literal proves nothing about the ones
			// before it, which still run.
			case LispNames.AND -> decidedTrue ? rest(head, parts) : LispNil.INSTANCE;
			case LispNames.OR -> decidedTrue ? LispTrue.INSTANCE : rest(head, parts);
			case LispNames.NOT, LispNames.NULL ->
				parts.size() != 2 ? null : decidedTrue ? LispNil.INSTANCE : LispTrue.INSTANCE;
			default -> null;
		};
	}

	/** The body of a taken {@code when}/{@code unless} as ONE expression. */
	private static LispVal body(List<LispVal> parts) {
		return parts.size() == 3 ? parts.get(2) : properList(new LispSymbol(LispNames.PROGN), parts, 2);
	}

	/** The same {@code and}/{@code or} without its now-irrelevant first argument. */
	private static LispVal rest(LispSymbol head, List<LispVal> parts) {
		return parts.size() == 3 ? parts.get(2) : properList(head, parts, 2);
	}

	private static LispVal properList(LispSymbol head, List<LispVal> parts, int from) {
		LispVal tail = LispNil.INSTANCE;
		for (int i = parts.size() - 1; i >= from; i--) {
			tail = new LispCons(parts.get(i), tail);
		}
		return new LispCons(head, tail);
	}

	/**
	 * The answer for one probe argument, or null when it is not decidable here.
	 */
	private static @Nullable LispVal decide(LispVal arg, State state, boolean deferred) {
		LispVal datum = arg instanceof LispCons quote && quote.car() instanceof LispSymbol head
				&& LispNames.QUOTE.equals(member(head.name())) && quote.cdr() instanceof LispCons cell ? cell.car()
						: arg;
		// nil, t and every keyword are bound to themselves on every backend.
		if (datum instanceof LispNil || datum instanceof LispTrue) {
			return LispTrue.INSTANCE;
		}
		if (!(datum instanceof LispSymbol sym)) {
			return null;
		}
		if (sym.isKeyword()) {
			return LispTrue.INSTANCE;
		}
		// A literal symbol the program did not quote is a VARIABLE READ, not a name.
		if (datum == arg) {
			return null;
		}
		String name = sym.name();
		// A cl symbol may be born bound (the standard stream variables are), and which
		// ones the backends seed is their business -- leave every one of them to run.
		if (PackageRegistry.isClSymbol(name) || PackageRegistry.isClSymbol(member(name))
				|| state.poisoned.contains(name)) {
			return null;
		}
		if (deferred) {
			return state.everBound.contains(name) ? null : LispNil.INSTANCE;
		}
		if (state.packagesResolved && state.definitelyBefore.contains(name)) {
			return LispTrue.INSTANCE;
		}
		if (state.boundBefore.contains(name)) {
			return null;
		}
		return !state.here.contains(name) || state.prefixClean ? LispNil.INSTANCE : null;
	}

	// -- the top-level collapse ----------------------------------------------------

	/**
	 * Appends the form to the top-level list, collapsing an {@code if}/{@code when}/
	 * {@code unless} whose test the fold turned into a constant and splicing the
	 * surviving branch. This is what turns the discharged guard back into the plain
	 * {@code (defconstant +k+ v)} the file means -- and a top-level definer is what the
	 * tree-shaker and the top-level statement emitter can both act on.
	 */
	private static void emitReduced(LispVal form, List<LispVal> out) {
		LispVal current = form;
		while (current instanceof LispCons cons && cons.car() instanceof LispSymbol head && cons.isProperList()) {
			List<LispVal> parts = cons.toList();
			String op = member(head.name());
			// A malformed if is left whole so the error stays the compiler's.
			if (LispNames.IF.equals(op) && (parts.size() == 3 || parts.size() == 4)) {
				if (parts.get(1) instanceof LispTrue) {
					current = parts.get(2);
					continue;
				}
				if (parts.get(1) instanceof LispNil) {
					if (parts.size() == 3) {
						return;
					}
					current = parts.get(3);
					continue;
				}
			}
			else if ((LispNames.WHEN.equals(op) || LispNames.UNLESS.equals(op)) && parts.size() >= 2
					&& (parts.get(1) instanceof LispTrue || parts.get(1) instanceof LispNil)) {
				if (parts.get(1) instanceof LispTrue != LispNames.WHEN.equals(op)) {
					return;
				}
				for (int i = 2; i < parts.size(); i++) {
					emitReduced(parts.get(i), out);
				}
				return;
			}
			break;
		}
		out.add(current);
	}

	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	/**
	 * A set of variable names that also answers for the UNQUALIFIED spelling while
	 * packages are unresolved, where {@code +k+} in two packages is one string. Matching
	 * the short name there can only make a name look bound that is not, which blocks a
	 * fold -- the safe direction, and the reason the "bound" answer is withheld until the
	 * spellings are canonical.
	 */
	private static final class Names {

		private final boolean packagesResolved;

		private final Set<String> full = new HashSet<>();

		private final Set<String> members = new HashSet<>();

		private Names(boolean packagesResolved) {
			this.packagesResolved = packagesResolved;
		}

		private void add(String name) {
			this.full.add(name);
			if (!this.packagesResolved) {
				this.members.add(member(name));
			}
		}

		private boolean contains(String name) {
			return this.full.contains(name) || (!this.packagesResolved && this.members.contains(member(name)));
		}

	}

}
