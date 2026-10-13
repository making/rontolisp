package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * Backend-shared cut for the one shape the tail-spine body splitters cannot reach: a
 * BRANCH. {@code JvmBodyOutliner} splits a function body between two ITEMS of its tail
 * spine, so a decision tree that is one form -- {@code proc-parse}'s {@code match-i-case}
 * over the header bytes, which is {@code fast-http}'s two parsers -- has no split point
 * at all and stays over HotSpot's 8000-bytecode {@code HugeMethodLimit} for the lifetime
 * of the process ({@code .kb/hot-path-method-size.md}).
 *
 * <p>
 * The cut is made at the AST level, before {@code CrossLambdaExitLowering} runs: an
 * oversized sub-form {@code F} sitting in an EVALUATED position becomes
 *
 * <pre>{@code
 * (let ((__outlined_N (lambda () F))) (funcall __outlined_N))
 * }</pre>
 *
 * and every existing mechanism then does the rest -- closure conversion captures (and
 * boxes) the variables {@code F} reads and assigns, the cross-lambda exit lowering
 * rewrites a {@code go} or {@code return-from} that LEAVES {@code F} into a
 * {@code %nlx-throw} the establishing frame catches, and the backend emits {@code F} as
 * its own method. Nothing in either backend has to learn a new form.
 *
 * <p>
 * <b>It composes with the tail-spine splitter, and neither covers the other's shape.</b>
 * This pass cuts a BRANCH and cannot cut a run of statements; {@code JvmBodyOutliner}
 * cuts a run of statements but only along a method's tail spine. A sequence buried in a
 * branch arm is therefore reachable by neither -- until the arm becomes a method of its
 * own, and its statements ARE a tail spine. That is why an over-budget piece is still
 * outlined when it is a sequence, and why the wrapper is spelled as the {@code let}-bound
 * lambda an {@code flet} expands into rather than as {@code flet} (see {@code wrap}).
 *
 * <p>
 * <b>Only fully expanded, whitelisted positions are cut.</b> The walker descends through
 * a fixed list of operators and takes candidates only from positions those operators
 * EVALUATE -- an arm of an {@code if}, a {@code tagbody} statement, a
 * {@code return-from}'s value, a {@code cond} clause form. An operator it does not know
 * is left alone whole, so a misread position cannot silently turn data into code (the
 * mistake {@code LispMacroExpander.evaluatedClauseForms} exists to prevent); the cost of
 * not knowing an operator is only a cut not taken.
 *
 * <p>
 * <b>A plain call's arguments are cut.</b> An operator the whitelist does not case and no
 * denial below names is a function call, whose every argument evaluates exactly once,
 * left to right -- so a branch buried under calls (a top-level {@code (print
 * (case ...))}) is reachable, as is a call with a long argument list. Operators with
 * unevaluated positions (binding shapes, clause type heads, places, quoted data, exits)
 * are denied by name ({@code DENIED_CALL_HEADS}) and left whole.
 *
 * <p>
 * <b>Top-level forms are cut on request</b> ({@link #outlineTopLevel}): one form whose
 * own code passes the JVM's 64 KB per-method cap has no split point between forms, so its
 * branches and call arguments become methods of their own closing over nothing but the
 * form's own {@code let}s. Forms with a lexical exit are skipped -- this entry runs after
 * the exit lowering.
 *
 * <p>
 * <b>The budget is measured, not guessed.</b> Bytecodes per AST node ranges from 3.4 to
 * 39 across one program's methods (surface macros expand during the backend's own pass,
 * so the node count at this point does not predict the emitted size), which makes an
 * absolute node budget useless. So the caller does not set one: it compiles, MEASURES the
 * method that came out over the limit, and asks for a re-compile passing that function's
 * emitted size along with the size its pieces must fit in ({@link Budget}). The node
 * budget is then that function's own measured bytes-per-node ratio applied to the target,
 * and the compile that follows verifies it -- a function still over the limit simply
 * comes back with a tighter target.
 *
 */
public final class AstOutliner {

	/**
	 * What one function's outlining is calibrated against: the size the emitted method
	 * actually came out at, and the size its pieces should now fit in. The ratio between
	 * them is what turns this pass's node counts into a budget.
	 *
	 * @param measuredBytes the emitted method's bytecode length, from the compile that
	 * asked for the outlining
	 * @param targetBytes the bytecode size the residual method and each outlined piece
	 * should come in under
	 */
	public record Budget(int measuredBytes, int targetBytes) {
	}

	/**
	 * The rewritten program, the functions actually changed, the top-level form indexes
	 * actually cut, and -- for the compile that measures the outcome -- what budget, if
	 * any, is worth one more compile.
	 */
	public static final class Result {

		private final List<LispVal> input;

		private final List<LispVal> program;

		private final Set<String> outlined;

		private final Set<Integer> outlinedTop;

		private Result(List<LispVal> input, List<LispVal> program, Set<String> outlined) {
			this(input, program, outlined, Set.of());
		}

		private Result(List<LispVal> input, List<LispVal> program, Set<String> outlined, Set<Integer> outlinedTop) {
			this.input = input;
			this.program = program;
			this.outlined = outlined;
			this.outlinedTop = outlinedTop;
		}

		/**
		 * {@return the rewritten top-level forms}
		 */
		public List<LispVal> program() {
			return this.program;
		}

		/**
		 * {@return the names of the requested functions this pass really did cut} A name
		 * absent here cannot be helped by asking again.
		 */
		public Set<String> outlined() {
			return this.outlined;
		}

		/**
		 * {@return the top-level form indexes this pass really did cut} An index absent
		 * here cannot be helped by asking again.
		 */
		public Set<Integer> outlinedTop() {
			return this.outlinedTop;
		}

		/**
		 * The budget the next compile should cut {@code name} under, or null when no
		 * budget left to try would change what this compile emitted for it.
		 *
		 * <p>
		 * A compile costs seconds on a large program and learns nothing when the forms it
		 * compiles are the forms the previous one compiled -- the emitted size is a
		 * function of the forms. So the next budget is not merely the next target: the
		 * target keeps tightening (by two thirds, not past the floor) until the cut it
		 * produces DIFFERS from the one this compile used, which is checked here, on the
		 * AST, for the price of a walk. A function whose remaining targets all produce
		 * the same cut -- a flat {@code cond} of clauses each too small to move -- stops
		 * costing a compile per target; a function no target can cut at all is never
		 * asked for (mito's JVM compile: five attempts down to two). What ships is
		 * exactly what the target-by-target sequence shipped, because every attempt it
		 * skips compiled the same forms.
		 * @param name a function this compile measured over the limit
		 * @param measuredBytes the size it came out at
		 * @param current the budget this compile cut it under, or null when none was
		 * asked for
		 * @param firstTargetBytes the target a first cut aims at
		 * @param floorTargetBytes the target past which no further tightening is tried
		 * @return the next budget, or null when asking again cannot change the outcome
		 */
		public @Nullable Budget nextBudget(String name, int measuredBytes, @Nullable Budget current,
				int firstTargetBytes, int floorTargetBytes) {
			if (current == null) {
				Budget first = new Budget(measuredBytes, firstTargetBytes);
				return changesCut(name, null, first) ? first : null;
			}
			if (!this.outlined.contains(name)) {
				// Asked for and not cut: no tighter target finds a candidate either.
				return null;
			}
			Budget next = current;
			while (next.targetBytes() > floorTargetBytes) {
				next = new Budget(next.measuredBytes(), next.targetBytes() * 2 / 3);
				if (changesCut(name, current, next)) {
					return next;
				}
			}
			return null;
		}

		/**
		 * The budget the next compile should cut top-level form {@code index} under, or
		 * null when no budget left to try would change what this compile emitted for it.
		 * The same differ-or-stop rule as {@link #nextBudget}: a form whose remaining
		 * targets all produce the same cut stops costing a compile per target.
		 * @param index the top-level form this compile measured over the limit
		 * @param measuredBytes the size it came out at
		 * @param current the budget this compile cut it under, or null when none was
		 * asked for
		 * @param firstTargetBytes the target a first cut aims at
		 * @param floorTargetBytes the target past which no further tightening is tried
		 * @return the next budget, or null when asking again cannot change the outcome
		 */
		public @Nullable Budget nextTopBudget(int index, int measuredBytes, @Nullable Budget current,
				int firstTargetBytes, int floorTargetBytes) {
			if (current == null) {
				Budget first = new Budget(measuredBytes, firstTargetBytes);
				return changesTopCut(index, null, first) ? first : null;
			}
			if (!this.outlinedTop.contains(index)) {
				// Asked for and not cut: no tighter target finds a candidate either.
				return null;
			}
			Budget next = current;
			while (next.targetBytes() > floorTargetBytes) {
				next = new Budget(next.measuredBytes(), next.targetBytes() * 2 / 3);
				if (changesTopCut(index, current, next)) {
					return next;
				}
			}
			return null;
		}

		/**
		 * {@return whether cutting top-level form {@code index} under {@code to} produces
		 * another form than under {@code from}} A null {@code from} is the uncut form.
		 */
		private boolean changesTopCut(int index, @Nullable Budget from, Budget to) {
			if (index < 0 || index >= this.input.size()) {
				return false;
			}
			LispVal form = this.input.get(index);
			LispVal before = from == null ? form : new Cutter(false).cutTopLevel(form, from);
			LispVal after = new Cutter(false).cutTopLevel(form, to);
			return !sameForm(before, after);
		}

		/**
		 * {@return whether cutting every top-level defun named {@code name} under
		 * {@code to} produces other forms than under {@code from}} A null {@code from} is
		 * the uncut function.
		 */
		private boolean changesCut(String name, @Nullable Budget from, Budget to) {
			for (LispVal form : this.input) {
				if (!name.equals(defunName(form))) {
					continue;
				}
				LispVal before = from == null ? form : new Cutter(false).cutFunction((LispCons) form, from);
				LispVal after = new Cutter(false).cutFunction((LispCons) form, to);
				if (!sameForm(before, after)) {
					return true;
				}
			}
			return false;
		}

	}

	/**
	 * The smallest sub-form worth its own method, in nodes. Below this the closure and
	 * the indirect call cost more than the cut buys.
	 */
	private static final int MIN_CUT_NODES = 40;

	/** No function is cut into pieces smaller than this, whatever the ratio says. */
	private static final int MIN_BUDGET_NODES = 300;

	/** What the {@code let}/{@code lambda}/{@code funcall} wrapper costs, in nodes. */
	private static final int WRAP_NODES = 12;

	/** A lambda body is a method of its own, so it does not count toward this one. */
	private static final int LAMBDA_NODES = 8;

	private AstOutliner() {
	}

	/**
	 * Cuts every named function into pieces that fit its budget.
	 * @param program the top-level forms, macro-expanded, before
	 * {@code CrossLambdaExitLowering}
	 * @param budgets the functions to cut, by name, each with the calibration measured
	 * from the compile that asked
	 * @return the rewritten program and the names actually cut
	 */
	public static Result outline(List<LispVal> program, Map<String, Budget> budgets) {
		if (budgets.isEmpty()) {
			return new Result(program, program, Set.of());
		}
		Cutter cutter = new Cutter(System.getProperty("rontolisp.debug.outline") != null);
		List<LispVal> out = new ArrayList<>(program.size());
		Set<String> outlined = new LinkedHashSet<>();
		for (LispVal form : program) {
			LispVal rewritten = form;
			String name = defunName(form);
			Budget budget = name == null ? null : budgets.get(name);
			if (budget != null) {
				LispVal cut = cutter.cutFunction((LispCons) form, budget);
				if (cut != form) {
					rewritten = cut;
					outlined.add(name);
				}
			}
			out.add(rewritten);
		}
		return new Result(program, out, outlined);
	}

	/**
	 * Cuts over-limit top-level forms into pieces that fit their budget. A top-level form
	 * has no enclosing method to split between, so a form whose own emitted code passes
	 * the JVM's 64 KB per-method cap is cut the way an oversized defun body is: an
	 * oversized evaluated sub-form becomes a local function of its own ({@code wrap}),
	 * which the backend then emits as a method beside the top-level chunk. A top-level
	 * form closes over nothing but its own {@code let}s, so the pieces are calls that
	 * carry no continuation state.
	 *
	 * <p>
	 * Unlike {@link #outline}, this runs after every lowering (on the JVM backend, just
	 * before the top-level chunks are emitted), so the exit lowering has already run: a
	 * form containing a lexical exit ({@code go}, {@code return-from}, {@code return}) is
	 * left whole -- wrapping a piece of it in a fresh lambda would strand the exit from
	 * its establishing construct. Such a form keeps the loud over-limit failure it has
	 * today.
	 * @param topLevel the top-level forms about to be emitted, in emission order
	 * @param budgets the form indexes to cut, each with the calibration measured from the
	 * compile that asked
	 * @return the rewritten forms and the indexes actually cut
	 */
	public static Result outlineTopLevel(List<LispVal> topLevel, Map<Integer, Budget> budgets) {
		if (budgets.isEmpty()) {
			return new Result(topLevel, topLevel, Set.of(), Set.of());
		}
		Cutter cutter = new Cutter(System.getProperty("rontolisp.debug.outline") != null);
		List<LispVal> out = new ArrayList<>(topLevel.size());
		Set<Integer> outlinedTop = new LinkedHashSet<>();
		for (int i = 0; i < topLevel.size(); i++) {
			LispVal form = topLevel.get(i);
			LispVal rewritten = form;
			Budget budget = budgets.get(i);
			if (budget != null) {
				LispVal cut = cutter.cutTopLevel(form, budget);
				if (cut != form) {
					rewritten = cut;
					outlinedTop.add(i);
				}
			}
			out.add(rewritten);
		}
		return new Result(topLevel, out, Set.of(), outlinedTop);
	}

	/**
	 * {@return whether two cuts of one function are the same forms} The uncut parts of
	 * both are the input's own conses, so identity settles most of the walk; what a cut
	 * built is fresh on each side and compared by shape, a symbol by its name (the
	 * {@code __outlined_N} a fresh cutter numbers identically for the same cut).
	 */
	private static boolean sameForm(LispVal a, LispVal b) {
		while (a != b) {
			if (a instanceof LispCons ca && b instanceof LispCons cb) {
				if (!sameForm(ca.car(), cb.car())) {
					return false;
				}
				a = ca.cdr();
				b = cb.cdr();
			}
			else {
				return a instanceof LispSymbol sa && b instanceof LispSymbol sb && sa.name().equals(sb.name());
			}
		}
		return true;
	}

	/**
	 * {@return the name of a top-level {@code (defun name ...)}, else null}
	 */
	private static @Nullable String defunName(LispVal form) {
		if (form instanceof LispCons cons && cons.isProperList() && cons.car() instanceof LispSymbol head
				&& LispNames.DEFUN.equals(head.name()) && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol name) {
			return name.name();
		}
		return null;
	}

	private static final class Cutter {

		private final IdentityHashMap<LispVal, Integer> nodeCounts = new IdentityHashMap<>();

		private int counter;

		/** Whether each cut is reported ({@code -Drontolisp.debug.outline}). */
		private final boolean report;

		private Cutter(boolean report) {
			this.report = report;
		}

		/**
		 * Cuts one {@code (defun name lambda-list body...)} so the body left in place,
		 * and each piece taken out of it, fits the budget.
		 */
		private LispVal cutFunction(LispCons defun, Budget budget) {
			List<LispVal> parts = defun.toList();
			if (parts.size() < 4) {
				return defun;
			}
			// The body is a sequence, and a sequence is what the progn arm of the
			// whitelist already knows how to cut -- so it is cut AS one, then spliced
			// back. A (declare ...) among the items is never a candidate, so the
			// prologue stays exactly where it was.
			List<LispVal> bodyForm = new ArrayList<>();
			bodyForm.add(new LispSymbol(LispNames.PROGN));
			bodyForm.addAll(parts.subList(3, parts.size()));
			LispVal body = list(bodyForm);
			int nodes = nodes(body);
			if (budget.measuredBytes() <= 0) {
				return defun;
			}
			int nodeBudget = Math.max(MIN_BUDGET_NODES,
					(int) ((long) nodes * budget.targetBytes() / budget.measuredBytes()));
			LispVal cut = cut(body, nodeBudget);
			if (this.report) {
				System.err.println("[outline] " + ((LispCons) defun.cdr()).car().print() + " nodes=" + nodes
						+ " measured=" + budget.measuredBytes() + " target=" + budget.targetBytes() + " nodeBudget="
						+ nodeBudget + " pieces=" + this.counter);
			}
			if (cut == body) {
				return defun;
			}
			List<LispVal> cutParts = ((LispCons) cut).toList();
			List<LispVal> out = new ArrayList<>(parts.subList(0, 3));
			out.addAll(cutParts.subList(1, cutParts.size()));
			return list(out);
		}

		/**
		 * Cuts one top-level form so what is left in place, and each piece taken out of
		 * it, fits the budget. The form is cut AS the sequence it is: a top-level
		 * {@code progn} of one is the common oversized shape (one Clojure datum lowers to
		 * one form), and a single call such as {@code (print (case ...))} is cut through
		 * its evaluated arguments.
		 *
		 * <p>
		 * A form containing a lexical exit ({@code go}, {@code return-from},
		 * {@code return}) is returned whole: this entry runs after the exit lowering, so
		 * a fresh lambda around a piece of it would strand the exit from the construct
		 * that establishes it.
		 */
		private LispVal cutTopLevel(LispVal form, Budget budget) {
			if (containsLexicalExit(form)) {
				return form;
			}
			int nodes = nodes(form);
			if (budget.measuredBytes() <= 0) {
				return form;
			}
			int nodeBudget = Math.max(MIN_BUDGET_NODES,
					(int) ((long) nodes * budget.targetBytes() / budget.measuredBytes()));
			LispVal cut = cut(form, nodeBudget);
			if (this.report) {
				System.err.println("[outline-top] nodes=" + nodes + " measured=" + budget.measuredBytes() + " target="
						+ budget.targetBytes() + " nodeBudget=" + nodeBudget + " pieces=" + this.counter);
			}
			return cut;
		}

		/**
		 * {@return whether {@code form} contains a lexical exit anywhere, quoted data
		 * included} Over-approximate on purpose: a false positive only leaves the form
		 * whole, while wrapping a real exit in a fresh lambda would strand it.
		 */
		private static boolean containsLexicalExit(LispVal form) {
			while (form instanceof LispCons cons) {
				if (cons.car() instanceof LispSymbol head) {
					String name = head.name();
					int colon = name.lastIndexOf(':');
					String plain = colon < 0 ? name : name.substring(colon + 1);
					if ("GO".equals(plain) || "RETURN-FROM".equals(plain) || "RETURN".equals(plain)) {
						return true;
					}
				}
				if (containsLexicalExit(cons.car())) {
					return true;
				}
				form = cons.cdr();
			}
			return false;
		}

		/**
		 * {@return {@code form} with enough of its evaluated sub-forms moved into their
		 * own local functions that what is left fits {@code budget} nodes}
		 *
		 * The same form back (by identity) when nothing could be cut.
		 */
		private LispVal cut(LispVal form, int budget) {
			if (!(form instanceof LispCons cons) || !cons.isProperList()) {
				return form;
			}
			int nodes = nodes(form);
			if (nodes <= budget) {
				return form;
			}
			List<LispVal> parts = cons.toList();
			if (parts.isEmpty() || !(parts.get(0) instanceof LispSymbol head)) {
				return form;
			}
			List<Child> children = new ArrayList<>(children(parts, head.name()));
			if (children.isEmpty()) {
				return form;
			}
			children.sort(Comparator.comparingInt((Child child) -> nodes(child.form())).reversed());
			Map<Child, LispVal> replacements = new HashMap<>();
			int kept = nodes;
			for (Child child : children) {
				if (kept <= budget) {
					break;
				}
				int childNodes = nodes(child.form());
				int rest = kept - childNodes;
				if (rest <= budget && budget - rest >= budget / 2) {
					// Everything but this child already fits, and there is real room
					// left for it: keep it in place and cut
					// INSIDE it, so a form whose whole size is one nested sub-form (the
					// (or (with-octets-parsing ...) (error 'eof)) spine) does not pay a
					// closure for a cut that moves the problem rather than splitting it.
					// Once the siblings have eaten half the budget the child is better
					// off as its own method, where it gets the WHOLE budget again --
					// without that floor an in-place descent hands the depths a budget
					// of a dozen nodes and shreds them into closures.
					LispVal inner = cut(child.form(), budget - rest);
					int innerNodes = nodes(inner);
					if (rest + innerNodes > budget && (innerNodes <= budget || tailSplittable(inner))) {
						// Moving it out is what makes the parent fit -- and it is worth a
						// closure only when the piece itself now fits. A sub-form the
						// walk
						// could not cut small enough stays where it is: outlining it
						// would
						// relocate the oversized method rather than split it, and charge
						// an
						// indirect call for the move.
						replacements.put(child, wrap(inner));
					}
					else if (inner != child.form()) {
						replacements.put(child, inner);
					}
					break;
				}
				if (childNodes < MIN_CUT_NODES) {
					// Sorted largest first, so nothing bigger is left to try.
					break;
				}
				replacements.put(child, wrap(cut(child.form(), budget)));
				kept = rest + WRAP_NODES;
			}
			LispVal rebuilt = replacements.isEmpty() ? form : rebuild(parts, replacements);
			if (LispNames.COND.equals(head.name()) && nodes(rebuilt) > budget) {
				return splitClauses(((LispCons) rebuilt).toList(), budget, rebuilt);
			}
			return rebuilt;
		}

		/**
		 * {@return a {@code cond} still over {@code budget} once its big forms moved,
		 * with its clause LIST cut} What is left is clauses each too small to move -- the
		 * generated {@code %condition-report-str} is hundreds of them -- and no branch
		 * cut helps. So the clauses past the longest prefix that fits move into a final
		 * {@code (t ...)} clause whose body is the rest of the {@code cond}, as a piece
		 * of its own, cut the same way in turn: {@code (cond c1 .. ck ck+1 .. cn)} is
		 * {@code (cond c1 .. ck (t (cond ck+1 .. cn)))} exactly, the no-match nil
		 * included. A clause is never split.
		 */
		private LispVal splitClauses(List<LispVal> parts, int budget, LispVal form) {
			// The operator, the list's end, and the (t piece) clause the tail becomes.
			int used = 2 + 3 + WRAP_NODES;
			int keep = 1;
			while (keep < parts.size() && (keep == 1 || used + nodes(parts.get(keep)) <= budget)) {
				used += nodes(parts.get(keep));
				keep++;
			}
			if (keep >= parts.size() - 1) {
				// Nothing past the prefix, or a single clause: moving it buys nothing.
				return form;
			}
			List<LispVal> tail = new ArrayList<>();
			tail.add(parts.get(0));
			tail.addAll(parts.subList(keep, parts.size()));
			List<LispVal> out = new ArrayList<>(parts.subList(0, keep));
			out.add(list(List.of(LispTrue.INSTANCE, wrap(cut(list(tail), budget)))));
			return list(out);
		}

		/**
		 * Wraps one form as its own local function, called where the form stood.
		 *
		 * <p>
		 * Spelled as the {@code let}-bound lambda an {@code flet} expands into rather
		 * than as {@code flet} itself, for one reason: {@code flet} gives its local the
		 * block CL mandates, and a body wrapped in a {@code block} is a single item, so
		 * the tail-spine splitter would find nothing to cut inside the piece. Written
		 * this way the two splitters COMPOSE -- this pass cuts the branch, and
		 * {@code JvmBodyOutliner} then cuts the sequence inside the piece it made. No
		 * block is lost: nothing can name a local this pass invented.
		 */
		private LispVal wrap(LispVal form) {
			LispSymbol name = new LispSymbol("__outlined_" + (this.counter++));
			LispVal lambda = list(List.of(new LispSymbol(LispNames.LAMBDA), LispNil.INSTANCE, form));
			LispVal binding = list(List.of(name, lambda));
			return list(List.of(new LispSymbol(LispNames.LET), list(List.of(binding)),
					list(List.of(new LispSymbol(LispNames.FUNCALL), name))));
		}

		private LispVal rebuild(List<LispVal> parts, Map<Child, LispVal> replacements) {
			List<LispVal> out = new ArrayList<>(parts);
			Map<Integer, List<LispVal>> clauses = new HashMap<>();
			for (Map.Entry<Child, LispVal> entry : replacements.entrySet()) {
				Child child = entry.getKey();
				if (child.clauseIndex() < 0) {
					out.set(child.index(), entry.getValue());
				}
				else {
					clauses.computeIfAbsent(child.index(), i -> new ArrayList<>(((LispCons) parts.get(i)).toList()))
						.set(child.clauseIndex(), entry.getValue());
				}
			}
			clauses.forEach((index, clauseParts) -> out.set(index, list(clauseParts)));
			return list(out);
		}

		/**
		 * {@return the size of {@code form} in AST nodes, counting a nested
		 * {@code lambda} as a constant}
		 *
		 * A lambda body compiles to a method of its own, so its size is not this
		 * method's; what the enclosing method pays is the closure construction.
		 */
		private int nodes(LispVal form) {
			if (!(form instanceof LispCons)) {
				return 1;
			}
			Integer memo = this.nodeCounts.get(form);
			if (memo != null) {
				return memo;
			}
			int total = 0;
			LispVal rest = form;
			while (rest instanceof LispCons cell) {
				if (rest == form && cell.car() instanceof LispSymbol head && LispNames.LAMBDA.equals(head.name())) {
					total = LAMBDA_NODES;
					this.nodeCounts.put(form, total);
					return total;
				}
				total += nodes(cell.car());
				rest = cell.cdr();
			}
			total += 1;
			this.nodeCounts.put(form, total);
			return total;
		}

	}

	/**
	 * One candidate position: {@code parts[index]}, or {@code parts[index][clauseIndex]}
	 * when the operator's argument is a clause rather than a form.
	 */
	private record Child(int index, int clauseIndex, LispVal form) {
	}

	/**
	 * {@return the positions of {@code parts} that the operator {@code head} EVALUATES}
	 *
	 * Deliberately a whitelist over fully expanded forms: an unknown operator answers
	 * nothing and is left whole, which costs a cut and never miscompiles.
	 */
	private static List<Child> children(List<LispVal> parts, String head) {
		List<Child> out = new ArrayList<>();
		switch (head) {
			case LispNames.PROGN, LispNames.LOCALLY, LispNames.WHEN, LispNames.UNLESS, LispNames.AND, LispNames.OR,
					LispNames.PROG1, LispNames.PROG2 ->
				addForms(out, parts, 1);
			case LispNames.IF -> {
				for (int i = 1; i < Math.min(4, parts.size()); i++) {
					addForm(out, parts, i);
				}
			}
			// flet/labels: a DEFINITION body is a method of its own already, so only the
			// body runs in this frame.
			case LispNames.BLOCK, LispNames.LET, LispNames.LET_STAR, LispNames.FLET, LispNames.LABELS ->
				addForms(out, parts, 2);
			case LispNames.MULTIPLE_VALUE_BIND -> addForms(out, parts, 3);
			case LispNames.TAGBODY -> {
				// A label is an atom and a statement is a form; only the forms are code.
				for (int i = 1; i < parts.size(); i++) {
					if (parts.get(i) instanceof LispCons) {
						addForm(out, parts, i);
					}
				}
			}
			case LispNames.RETURN_FROM -> addForm(out, parts, 2);
			case LispNames.RETURN -> addForm(out, parts, 1);
			case LispNames.THE -> addForm(out, parts, 2);
			case LispNames.SETQ -> {
				for (int i = 2; i < parts.size(); i += 2) {
					addForm(out, parts, i);
				}
			}
			case LispNames.SETF, LispNames.PSETQ -> {
				// A place-Value pair per two positions: the values are ordinary
				// evaluated forms, while a place is not a value and never moves.
				for (int i = 1; i + 1 < parts.size(); i += 2) {
					addForm(out, parts, i + 1);
				}
			}
			case LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> {
				// (defvar name init doc): the initform is evaluated once, the name and
				// the docstring are not code.
				addForm(out, parts, 2);
			}
			case LispNames.COND -> {
				// (cond (test body...) ...): every form of every clause is code.
				for (int i = 1; i < parts.size(); i++) {
					addClauseForms(out, parts, i, 0);
				}
			}
			case LispNames.CASE, LispNames.ECASE, LispNames.TYPECASE, LispNames.ETYPECASE -> {
				// The keys of a clause are DATA; only the key form and the clause
				// bodies are code.
				addForm(out, parts, 1);
				for (int i = 2; i < parts.size(); i++) {
					addClauseForms(out, parts, i, 1);
				}
			}
			default -> {
				// A plain call: every argument is evaluated exactly once, left to
				// right, so each is an independent subexpression a method of its own
				// can compute. This is what reaches a branch buried under calls -- a
				// top-level (print (case ...)) is one call around the whole decision
				// tree -- and what cuts a call with a long argument list.
				//
				// An operator this pass does not understand is NOT a plain call: its
				// positions may be binding shapes, type heads, places, or quoted data,
				// and reading one as code would silently turn data into code (the
				// mistake LispMacroExpander.evaluatedClauseForms exists to prevent).
				// Such a head is denied below and left whole, which costs a cut and
				// never miscompiles. Package-qualified spellings (cl:go, cl:quote)
				// deny like their bare names.
				String name = head;
				int colon = name.lastIndexOf(':');
				String plain = colon < 0 ? name : name.substring(colon + 1);
				if (!DENIED_CALL_HEADS.contains(plain)) {
					addForms(out, parts, 1);
				}
			}
		}
		return out;
	}

	/**
	 * Operator heads whose argument positions are not all evaluated, so a call to one is
	 * never read as a plain call. Groups: quoted data and designators; binding shapes
	 * (lambda lists, declarations, definitions, loopIteration heads); places; clause type
	 * heads and situations; multiple-value shapes whose multiplicity must stay
	 * transparent; condition registration. A denied head is left whole -- the cut it
	 * would have bought is lost, nothing else.
	 */
	private static final Set<String> DENIED_CALL_HEADS = Set.of(
			// Quoted data and designators: never code.
			"QUOTE", "%UNSPELLED-QUOTE", "FUNCTION",
			// Binding shapes: the names and lists around the code are data.
			"LAMBDA", "DECLARE", "DECLAIM", "PROCLAIM", "DEFUN", "DEFMACRO", "DEFTYPE", "DEFSTRUCT", "DEFCLASS",
			"DEFMETHOD", "DEFGENERIC", "DEFPACKAGE", "IN-PACKAGE", "MACROLET", "SYMBOL-MACROLET", "DESTRUCTURING-BIND",
			"WITH-SLOTS", "WITH-ACCESSORS", "WITH-HASH-TABLE-ITERATOR", "WITH-OPEN-FILE", "WITH-OUTPUT-TO-STRING",
			"WITH-INPUT-FROM-STRING", "PPRINT-LOGICAL-BLOCK", "DOTIMES", "DOLIST", "DO", "DO*", "LOOP", "PROG", "PROG*",
			"PROGV",
			// Places: not values.
			"PUSH", "POP", "PUSHNEW", "REMF", "ROTATEF", "INCF", "DECF", "SHIFTF", "DEFINE-MODIFY-MACRO",
			"MULTIPLE-VALUE-SETQ",
			// Multiple values: the values-form's multiplicity must stay transparent.
			"MULTIPLE-VALUE-CALL", "MULTIPLE-VALUE-LIST", "MULTIPLE-VALUE-PROG1", "NTH-VALUE",
			// Clause type heads, situations and conditions: data around the code.
			"HANDLER-CASE", "HANDLER-BIND", "RESTART-CASE", "RESTART-BIND", "WITH-SIMPLE-RESTART", "CHECK-TYPE",
			"ASSERT", "ERROR", "CERROR", "SIGNAL", "MAKE-CONDITION", "DEFINE-CONDITION", "DEFINE-SETF-EXPANDER",
			"DEFINE-COMPILER-MACRO",
			// Dynamic extent and load-time shapes, left whole.
			"CATCH", "UNWIND-PROTECT", "LOAD-TIME-VALUE", "EVAL-WHEN", "WITH-COMPILATION-UNIT",
			// Exits: moving a piece of one strands it (top-level cuts skip such forms
			// outright; this keeps the shared walker from descending into one).
			"GO", "RETURN", "RETURN-FROM", "BLOCK", "TAGBODY",
			// The rest of the clause family, which the whitelist above does not case.
			"CCASE", "CTYPECASE",
			// Backstop: every operator the whitelist above cases, so a
			// package-qualified spelling that missed the switch (cl:cond) is left
			// whole rather than read as a plain call. Unreachable for bare heads.
			"IF", "PROGN", "LOCALLY", "WHEN", "UNLESS", "AND", "OR", "PROG1", "PROG2", "LET", "LET*", "FLET", "LABELS",
			"MULTIPLE-VALUE-BIND", "THE", "SETQ", "SETF", "PSETQ", "DEFVAR", "DEFPARAMETER", "DEFCONSTANT", "COND",
			"CASE", "ECASE", "TYPECASE", "ETYPECASE");

	private static void addForms(List<Child> out, List<LispVal> parts, int from) {
		for (int i = from; i < parts.size(); i++) {
			addForm(out, parts, i);
		}
	}

	private static void addForm(List<Child> out, List<LispVal> parts, int index) {
		if (index >= parts.size()) {
			return;
		}
		LispVal form = parts.get(index);
		if (isDeclaration(form)) {
			return;
		}
		out.add(new Child(index, -1, form));
	}

	private static void addClauseForms(List<Child> out, List<LispVal> parts, int index, int from) {
		if (!(parts.get(index) instanceof LispCons clause) || !clause.isProperList()) {
			return;
		}
		List<LispVal> clauseParts = clause.toList();
		for (int j = from; j < clauseParts.size(); j++) {
			LispVal form = clauseParts.get(j);
			if (!isDeclaration(form)) {
				out.add(new Child(index, j, form));
			}
		}
	}

	/**
	 * {@return whether moving {@code form} into a method of its own hands it to the
	 * tail-spine splitter}
	 *
	 * A piece too big for the budget is still worth outlining when it is a SEQUENCE: this
	 * pass cuts branches and cannot cut a run of statements, while
	 * {@code JvmBodyOutliner} cuts exactly that -- but only along the tail spine of a
	 * method, which a sequence buried in a branch arm is not. Outlining it makes it one.
	 * The two splitters together cover both shapes; either alone leaves this one whole.
	 */
	private static boolean tailSplittable(LispVal form) {
		if (!(form instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
			return false;
		}
		int bodyFrom = switch (head.name()) {
			case LispNames.PROGN, LispNames.LOCALLY -> 1;
			case LispNames.LET, LispNames.LET_STAR, LispNames.FLET, LispNames.LABELS -> 2;
			default -> -1;
		};
		return bodyFrom >= 0 && cons.toList().size() - bodyFrom >= 2;
	}

	/** A {@code (declare ...)} is not code and never moves. */
	private static boolean isDeclaration(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.DECLARE.equals(head.name());
	}

	private static LispVal list(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

}
