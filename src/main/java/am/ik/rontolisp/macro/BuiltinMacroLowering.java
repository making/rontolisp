package am.ik.rontolisp.macro;

import java.util.HashSet;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * Dump-time lowering of built-in macro calls to the core forms beneath them, the last
 * expansion of {@code --dump-ir}. The compile path's front end expands only USER macros
 * ({@code UserMacroExpander}); the built-ins -- {@code cond}, {@code and}, {@code setf},
 * ... -- ride the IR unexpanded and each consumer lowers them where it stands: the
 * interpreter at eval time, the compilers during body codegen. This pass gives the DUMP
 * the same view, so a question about what a built-in macro did is one diff, not a read of
 * two expanders.
 *
 * <p>
 * It is NOT a pipeline stage: the backends keep expanding where they do, and the dump's
 * result must run identically -- which it does, because a lowered call is the same code
 * the consumer would have produced. The stance is {@code SpecialVarCollector}'s:
 * expansion is context-free here but the consumers expand with context, so a form the
 * expander rejects (it may validate shapes the compiler checks later) stays as it is, and
 * the pass never throws on a program the consumers accept.
 *
 * <p>
 * Shadowing: a {@code flet}/{@code labels}/{@code macrolet}/{@code symbol-macrolet}-local
 * name replaces the built-in of the same name in its body, so the walk carries the set of
 * locally bound names and leaves a shadowed call for the lexical semantics to resolve.
 * Quoted data is never entered, and {@code #'(lambda ...)} bodies are walked.
 */
public final class BuiltinMacroLowering {

	/**
	 * Head expansions applied to one form before the walk moves on: a guard against a
	 * pathologic expander whose output contains its own input, never reached by the
	 * terminating expanders in {@link LispMacroExpander#expandBuiltinMacro}.
	 */
	private static final int MAX_HEAD_EXPANSIONS = 64;

	private BuiltinMacroLowering() {
	}

	/**
	 * Lowers every built-in macro call in {@code form} to its core form. The shape-aware
	 * cases keep binding names, lambda lists and type specifiers out of the expression
	 * walk; everything else walks as plain call arguments.
	 * <p>
	 * Callers lower one already-split program form at a time: the {@code
	 * %struct-definition} unwrapping and the printing stay with them.
	 * @param form the form to lower
	 * @return the lowered form, the same reference where nothing changed
	 */
	public static LispVal lower(LispVal form) {
		return walk(form, Set.of());
	}

	private static LispVal walk(LispVal form, Set<String> shadowed) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (!(cons.car() instanceof LispSymbol head)) {
			// Not a call shape: the operator is itself an expression ((lambda ...) args,
			// (1 2 3) in a funcall) or the list is dotted -- walk the elements, no head
			// expansion.
			return LispCons.rebuilt(cons, walk(cons.car(), shadowed), walk(cons.cdr(), shadowed));
		}
		return switch (head.name()) {
			// Quoted data: none of the pass's business, whatever calls it holds.
			case LispNames.QUOTE -> cons;
			// #'fn stays a designator; #'(lambda ...) is code and its body is walked.
			case LispNames.FUNCTION -> walkFunction(cons, shadowed);
			// Declarations carry type specifiers, whose (and ...) is a type union, not
			// the logical form -- and no code: nothing in them to lower.
			case LispNames.DECLARE, LispNames.DECLAIM, LispNames.PROCLAIM -> cons;
			// (the type form...): the type specifier is data, the value forms are code.
			case LispNames.THE, LispNames.LAMBDA -> walkFrom(cons, 2, shadowed);
			// (let (binding...) body...) / let*: the binding names are shapes, the
			// initforms code.
			case LispNames.LET, LispNames.LET_STAR, LispNames.PROG, LispNames.PROG_STAR -> walkLet(cons, shadowed);
			// (do (var init step...) (end result...) body...): the same split, one
			// level into the steps and around the end pair.
			case LispNames.DO, LispNames.DO_STAR -> walkDo(cons, shadowed);
			// (defun name lambda-list body...) / (defun (setf name) ...): the name and
			// the lambda list are shapes, whichever shape the name takes.
			case LispNames.DEFUN, LispNames.DEFMETHOD -> walkDefun(cons, shadowed);
			// (handler-case form (type var body...)...): the form and every clause body
			// are code; a clause head is a condition TYPE designator -- (error ...) is
			// the type, not a call to expand.
			case LispNames.HANDLER_CASE, LispNames.RESTART_CASE -> walkHandlerCase(cons, shadowed);
			// (handler-bind ((type handler)...): the types are data, the handlers and
			// the body are code.
			case LispNames.HANDLER_BIND, LispNames.RESTART_BIND -> walkHandlerBind(cons, shadowed);
			// The local bindings shadow their names in the body: the bodies are walked,
			// the binding shapes kept and the names carried into the body's scope.
			case LispNames.FLET, LispNames.LABELS, LispNames.MACROLET, LispNames.SYMBOL_MACROLET ->
				walkLocalBindings(cons, head.name(), shadowed);
			default -> walkCall(cons, head.name(), shadowed);
		};
	}

	/**
	 * A call: head expansions to a fixpoint (a macro call may expand to another macro
	 * call), then the ordinary argument walk. A form the expander rejects, or a name a
	 * local binding shadows, walks raw with no expansion -- the consumers expand it, or
	 * the lexical binding answers, as the case may be.
	 */
	private static LispVal walkCall(LispCons cons, String name, Set<String> shadowed) {
		if (!shadowed.contains(name) && !HEADS_LEFT_AS_IS.contains(name)) {
			LispVal expansion = tryExpand(cons, MAX_HEAD_EXPANSIONS);
			if (expansion != null) {
				return walk(expansion, shadowed);
			}
		}
		return LispCons.rebuilt(cons, walk(cons.car(), shadowed), walk(cons.cdr(), shadowed));
	}

	/**
	 * Heads whose expansion this pass must leave alone, for two reasons. The condition
	 * designators ({@code error} and family): {@code expandError} lowers a computed datum
	 * to a {@code %error-runtime} call -- COMPILE-PATH machinery whose dispatcher defuns
	 * exist only beside an {@code expandTopLevelDefinitions} injection and whose registry
	 * is the expander's instant, not the dump reader's. The definition forms
	 * ({@code define-condition}, {@code deftype}, the def{setf,compiler-macro} pair):
	 * their expansion is REGISTRATION ({@code expandDefineCondition} answers nil and
	 * records the class elsewhere), which a context-free walk cannot carry -- the
	 * consumers consume the form itself. {@code make-condition}: the dispatch's one-arg
	 * expander is the registry-LESS fallback (a bare type-name string), not the instance
	 * construction the registry-bearing overload builds. The bodies still walk, as
	 * ordinary arguments.
	 */
	private static final Set<String> HEADS_LEFT_AS_IS = Set.of(LispNames.ERROR, LispNames.CERROR, LispNames.SIGNAL,
			LispNames.WARN, LispNames.DEFINE_CONDITION, LispNames.DEFTYPE, LispNames.DEFINE_SETF_EXPANDER,
			LispNames.DEFINE_COMPILER_MACRO, LispNames.MAKE_CONDITION);

	private static @Nullable LispVal tryExpand(LispCons cons, int budget) {
		if (budget <= 0) {
			return null;
		}
		LispVal expansion;
		try {
			expansion = LispMacroExpander.expandBuiltinMacro(cons);
		}
		catch (RuntimeException ignored) {
			// A malformed call is the consumer's error to report, not the dump's.
			return null;
		}
		if (expansion == null || expansion == cons) {
			return null;
		}
		// The expansion may be a macro call itself (case -> cond -> if): keep going.
		if (expansion instanceof LispCons expanded && expanded.isProperList()
				&& expanded.car() instanceof LispSymbol sym && !sym.name().equals(headName(cons))) {
			LispVal further = tryExpand(expanded, budget - 1);
			if (further != null) {
				return further;
			}
		}
		return expansion;
	}

	private static String headName(LispCons cons) {
		return cons.car() instanceof LispSymbol sym ? sym.name() : "";
	}

	private static LispVal walkFunction(LispCons cons, Set<String> shadowed) {
		if (cons.cdr() instanceof LispCons rest && rest.isProperList()) {
			// #'(lambda ...): the designator is a lambda form, walked as one; #'fn and a
			// malformed #' stay as they stand.
			return LispCons.rebuilt(cons, cons.car(),
					LispCons.rebuilt(rest, walkFunctionDesignator(rest.car(), shadowed), walk(rest.cdr(), shadowed)));
		}
		return cons;
	}

	private static LispVal walkFunctionDesignator(LispVal designator, Set<String> shadowed) {
		if (designator instanceof LispCons cons && cons.isProperList() && cons.car() instanceof LispSymbol head
				&& head.name().equals(LispNames.LAMBDA)) {
			return walkFrom(cons, 2, shadowed);
		}
		return designator;
	}

	/**
	 * (defun|defmethod name-and-qualifiers... lambda-list body...): the operator, the
	 * name, any symbol qualifiers and the lambda list are shapes; the body -- the tail
	 * from the second element after the last shape head -- is code. A setf function name
	 * or a specializer rides in the shape positions and is skipped with them.
	 */
	private static LispVal walkDefun(LispCons cons, Set<String> shadowed) {
		if (!(cons.cdr() instanceof LispCons rest)) {
			return cons;
		}
		return LispCons.rebuilt(cons, cons.car(), walkFrom(rest, 2, shadowed));
	}

	private static LispVal walkLet(LispCons cons, Set<String> shadowed) {
		if (!(cons.cdr() instanceof LispCons rest) || !(rest.car() instanceof LispCons bindings)
				|| !bindings.isProperList()) {
			return cons;
		}
		return LispCons.rebuilt(cons, cons.car(),
				LispCons.rebuilt(rest, walkBindingList(bindings, shadowed), walk(rest.cdr(), shadowed)));
	}

	private static LispVal walkDo(LispCons cons, Set<String> shadowed) {
		if (!(cons.cdr() instanceof LispCons rest) || !(rest.car() instanceof LispCons bindings)
				|| !bindings.isProperList() || !(rest.cdr() instanceof LispCons end) || !end.isProperList()) {
			return cons;
		}
		// (end result...): the end form and the result forms are code, the pair's parens
		// are not.
		LispVal walkedEnd = LispCons.rebuilt(end, walk(end.car(), shadowed), walk(end.cdr(), shadowed));
		return LispCons.rebuilt(cons, cons.car(),
				LispCons.rebuilt(rest, walkBindingList(bindings, shadowed), walkedEnd));
	}

	/**
	 * The binding list of let / let* / do: a cell (name init...) keeps its name and walks
	 * what follows it; anything else walks whole.
	 */
	private static LispVal walkBindingList(LispCons bindings, Set<String> shadowed) {
		LispVal car = bindings.car();
		LispVal walkedCar = car instanceof LispCons binding && binding.isProperList()
				&& binding.car() instanceof LispSymbol
						? LispCons.rebuilt(binding, binding.car(), walk(binding.cdr(), shadowed)) : walk(car, shadowed);
		LispVal walkedCdr = bindings.cdr() instanceof LispCons rest && rest.isProperList()
				? walkBindingList(rest, shadowed) : walk(bindings.cdr(), shadowed);
		return LispCons.rebuilt(bindings, walkedCar, walkedCdr);
	}

	/**
	 * (handler-case form clause...) / restart-case: the protected form and every clause
	 * body are code; a clause head is a condition type designator and its variable is a
	 * shape, so (error ...) as a clause head is the type, never the error call.
	 */
	private static LispVal walkHandlerCase(LispCons cons, Set<String> shadowed) {
		if (!(cons.cdr() instanceof LispCons rest)) {
			return cons;
		}
		LispVal walkedForm = walk(rest.car(), shadowed);
		LispVal walkedClauses = rest.cdr() instanceof LispCons clauses && clauses.isProperList()
				? walkNamedClauses(clauses, shadowed) : walk(rest.cdr(), shadowed);
		return LispCons.rebuilt(cons, cons.car(), LispCons.rebuilt(rest, walkedForm, walkedClauses));
	}

	/**
	 * (handler-bind ((type handler)...)&nbsp;body...) / restart-bind: a pair's type is
	 * data, its handler form is code; the body is code.
	 */
	private static LispVal walkHandlerBind(LispCons cons, Set<String> shadowed) {
		if (!(cons.cdr() instanceof LispCons rest) || !(rest.car() instanceof LispCons bindings)
				|| !bindings.isProperList()) {
			return cons;
		}
		return LispCons.rebuilt(cons, cons.car(),
				LispCons.rebuilt(rest, walkHandlerPairs(bindings, shadowed), walk(rest.cdr(), shadowed)));
	}

	/**
	 * The (type handler...) pairs of a handler-bind / the (name function-form ...) ones
	 * of a restart-bind: the head stays, the tail is code.
	 */
	private static LispVal walkHandlerPairs(LispCons pairs, Set<String> shadowed) {
		LispVal car = pairs.car();
		LispVal walkedCar = car instanceof LispCons pair && pair.isProperList()
				? LispCons.rebuilt(pair, pair.car(), walk(pair.cdr(), shadowed)) : walk(car, shadowed);
		LispVal walkedCdr = pairs.cdr() instanceof LispCons rest && rest.isProperList()
				? walkHandlerPairs(rest, shadowed) : walk(pairs.cdr(), shadowed);
		return LispCons.rebuilt(pairs, walkedCar, walkedCdr);
	}

	private static LispVal walkLocalBindings(LispCons cons, String operator, Set<String> shadowed) {
		if (!(cons.cdr() instanceof LispCons rest) || !(rest.car() instanceof LispCons defs) || !defs.isProperList()) {
			return cons;
		}
		Set<String> inner = new HashSet<>(shadowed);
		LispVal walkedDefs = walkDefList(defs, operator.equals(LispNames.SYMBOL_MACROLET), inner, shadowed);
		return LispCons.rebuilt(cons, cons.car(), LispCons.rebuilt(rest, walkedDefs, walk(rest.cdr(), inner)));
	}

	/**
	 * The (head fields...) clauses of a handler-case / restart-case: the head is data,
	 * everything from the third field on is code.
	 */
	private static LispVal walkNamedClauses(LispCons clauses, Set<String> shadowed) {
		LispVal car = clauses.car();
		// walkFrom answers the whole clause with its tail from the body on walked.
		LispVal walkedCar = car instanceof LispCons clause && clause.isProperList() ? walkFrom(clause, 2, shadowed)
				: walk(car, shadowed);
		LispVal walkedCdr = clauses.cdr() instanceof LispCons rest && rest.isProperList()
				? walkNamedClauses(rest, shadowed) : walk(clauses.cdr(), shadowed);
		return LispCons.rebuilt(clauses, walkedCar, walkedCdr);
	}

	/**
	 * The definition list, per def: for flet/labels/macrolet, (name lambda-list body...)
	 * with the name and lambda list shapes kept; for symbol-macrolet, (name expansion)
	 * with the expansion code in the OUTER scope. Every def's name joins the inner scope,
	 * even when the def itself walks raw.
	 */
	private static LispVal walkDefList(LispCons defs, boolean symbolMacros, Set<String> inner, Set<String> outer) {
		LispVal car = defs.car();
		LispVal walkedCar = car;
		if (car instanceof LispCons def && def.isProperList() && def.car() instanceof LispSymbol sym) {
			inner.add(sym.name());
			// Past the name: the lambda list (a shape) then the body -- or, for
			// symbol-macrolet, the expansion straight away.
			walkedCar = LispCons.rebuilt(def, def.car(), symbolMacros ? walk(def.cdr(), outer)
					: def.cdr() instanceof LispCons tail ? walkFrom(tail, 1, outer) : walk(def.cdr(), outer));
		}
		LispVal walkedCdr = defs.cdr() instanceof LispCons rest && rest.isProperList()
				? walkDefList(rest, symbolMacros, inner, outer) : walk(defs.cdr(), outer);
		return LispCons.rebuilt(defs, walkedCar, walkedCdr);
	}

	/**
	 * Walks the tail of {@code cons} from its {@code from}-th element on (the operator is
	 * element zero), keeping everything before it as it stands.
	 */
	private static LispVal walkFrom(LispCons cons, int from, Set<String> shadowed) {
		if (from <= 0) {
			return walk(cons, shadowed);
		}
		if (!(cons.cdr() instanceof LispCons next)) {
			return cons; // improper below the split: the shape is the reader's to judge
		}
		return LispCons.rebuilt(cons, cons.car(), walkFrom(next, from - 1, shadowed));
	}

}
