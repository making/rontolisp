package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.FormatRenderer;

/**
 * Whether a program can name a function at run time with a spelling this compile never
 * sees -- the one question behind the compile paths' funcall-dispatch gate
 * ({@code Wasm/JvmLispCompiler.dispatchableFuncIds},
 * {@code .kb/optimize-dead-code-elimination.md}).
 *
 * <p>
 * That gate gives a function a dispatcher case only when the program materializes it as a
 * value or spells its name as a compile-time constant, so {@code --optimize} can drop
 * everything reachable only through the dispatch ladder. It is sound exactly while no
 * operator can conjure a name out of runtime data. This class answers "can one?", and
 * lives in {@code compiler} because BOTH compile backends have to answer it identically:
 * a program that keeps resolving a forged designator on one backend and stops on the
 * other is the divergence one shared answer exists to prevent.
 *
 * <p>
 * <strong>Only the data evaluators hold the gate open.</strong> {@code (eval (read))}
 * calls a function whose name exists only in the input stream, so no probe of the
 * module's own constants can cover it -- every function must stay dispatchable. The
 * symbol BUILDERS ({@code intern}, {@code find-symbol}, {@code make-symbol},
 * {@code symbol-function}, {@code fdefinition}, {@code fboundp},
 * {@code uiop:symbol-call}) used to force the same full bail, and no longer do: a symbol
 * they produce is built FROM A STRING, and any string the program holds is a compile-time
 * constant the {@code dispatchableFuncIds} probes already read -- the canonical name, its
 * alias spelling, the bare member, each in symbol, framed-string-literal and keyword
 * spelling. The framed-string and keyword spellings are probed only while a builder from
 * {@link #anySymbolBuilder} is present at all -- without one, no runtime path can turn a
 * string constant into a designator, so a gate-closed program stops paying rows for
 * defuns whose member name merely collides with an unrelated literal. What escapes those
 * probes is a name assembled out of COMPUTED pieces ({@code concatenate} et al.), and
 * that is precisely the carve-out {@code LibraryDefunPruner} has always documented: the
 * forged name gets the ordinary undefined-function error, and {@code --dynamic} restores
 * late binding. Splitting the two classes is what lets a clack Worker module -- whose
 * handler discovery is {@code (find-symbol "RUN" pkg)} over names the module spells --
 * keep its call-only defuns shakeable instead of keeping all of them dispatchable.
 *
 * <p>
 * <strong>Trigger-shaped, not dataflow-shaped, and deliberately so.</strong> The precise
 * question is whether a symbol out of one of these operators can reach a funcall; getting
 * that wrong is a trap at run time rather than a diagnosis, so the rule stays syntactic
 * and over-approximates -- an {@code eval}/{@code read} occurrence counts anywhere,
 * quoted data included (the operator could be extracted out of the data and applied).
 *
 * <p>
 * Run any compile with {@code -Drontolisp.debug.dispatchgate=true} to have the offending
 * operator named.
 */
public final class RuntimeNameProducers {

	private RuntimeNameProducers() {
	}

	/**
	 * Operators that turn data into code: {@code (eval (read))} calls a function whose
	 * name exists only in the input stream. ({@code read}/{@code load} occurrences also
	 * reach the gate through the backends' own {@code usesRead}/{@code usesLoad} flags;
	 * this scan additionally counts them inside quoted data.)
	 *
	 * <p>
	 * {@link FormatRenderer#FUNCTION_DESIGNATOR} is in the set for the same reason it
	 * exists at all: the {@code ~/name/} arm resolves a function out of a CONTROL STRING,
	 * which is runtime data, and the arm is injected precisely when the program can be
	 * seen to render one -- so its presence is the trigger, and the stub a directive-free
	 * program gets instead never fires it.
	 */
	private static final Set<String> EVALUATES_DATA = Set.of(LispNames.EVAL, LispNames.READ, LispNames.READ_FROM_STRING,
			LispNames.LOAD, FormatRenderer.FUNCTION_DESIGNATOR);

	/**
	 * The symbol BUILDERS: the operators that can turn a STRING (or, through
	 * {@code uiop:symbol-call}'s lowering, a KEYWORD) the program holds into a symbol at
	 * run time. Their presence is what makes the framed-string-literal and keyword
	 * spellings of a defun's name reachable as designators, so
	 * {@code dispatchableFuncIds} widens its probes to those spellings exactly when one
	 * occurs -- without one, no runtime path can turn a string constant into a
	 * designator, and the symbol/alias/member probes suffice. The list matches the WASM
	 * backend's {@code usesIntern} trigger ({@code intern}, {@code find-symbol},
	 * {@code uiop:symbol-call} -- the operators that wire the {@code _intern} runtime a
	 * string-built designator resolves through), plus {@code make-symbol}: its product
	 * cannot match a registry row on WASM (a fresh, never-canonicalized offset), but the
	 * JVM registry compares string VALUES, so it is kept in the set as the safe
	 * over-approximation -- a kept row costs bytes, a missing one costs a resolution.
	 */
	private static final Set<String> BUILDS_SYMBOLS = Set.of(LispNames.INTERN, LispNames.FIND_SYMBOL,
			LispNames.MAKE_SYMBOL, LispNames.UIOP_SYMBOL_CALL);

	/**
	 * Whether the program can resolve a function name this compile never sees spelled
	 * out.
	 * @param program the program, after every AST pass the backend runs before codegen
	 * @return true when the funcall-dispatch gate must keep every function dispatchable
	 */
	public static boolean anyNameResolvable(List<LispVal> program) {
		for (LispVal form : program) {
			if (scan(form)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the program contains a symbol BUILDER, i.e. whether a string or keyword
	 * constant it holds can become a function designator at run time. Decides whether
	 * {@code dispatchableFuncIds} applies its framed-string-literal and keyword probes
	 * (both backends ask, and must agree).
	 *
	 * <p>
	 * Two of the compiler's own emissions do not count, because each can be seen -- from
	 * its shape alone -- never to produce a FUNCTION designator, and each rides into
	 * every Worker/serve program whether or not anything in it can build one. The literal
	 * call shape {@code (intern X :keyword)} only produces a KEYWORD, and a keyword can
	 * never name a defun (no registry row's key begins with a colon); it is how the
	 * spliced {@code http-server.lisp} interns the request method and protocol. The
	 * injected {@code (defun %slot-name-key (n) (intern (symbol-name n)))} is the runtime
	 * slot-name fold ({@code LispMacroExpander.slotNameKeyDefun}), whose product feeds
	 * the slot dispatchers' {@code member} arms -- the identity exemption the fold was
	 * made a defun of its own to allow. Only those exact shapes are exempt --
	 * {@code #'intern}, a computed package, a user's own {@code (intern (symbol-name
	 * x))}, any other spelling still counts -- and the exempted call's arguments are
	 * still scanned.
	 * @param program the program, after every AST pass the backend runs before codegen
	 * @return true when the widened designator-spelling probes apply
	 */
	public static boolean anySymbolBuilder(List<LispVal> program) {
		for (LispVal form : program) {
			if (!isSlotNameKeyDefun(form) && scanBuilders(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean scanBuilders(LispVal val) {
		if (val instanceof LispSymbol sym) {
			return BUILDS_SYMBOLS.contains(sym.name());
		}
		LispVal cur = val;
		if (isKeywordPackageIntern(cur)) {
			// Skip the head; the name argument may still hold a builder of its own.
			return scanBuilders(((LispCons) ((LispCons) cur).cdr()).car());
		}
		while (cur instanceof LispCons cell) {
			if (scanBuilders(cell.car())) {
				return true;
			}
			cur = cell.cdr();
		}
		return cur instanceof LispSymbol && scanBuilders(cur);
	}

	/** The injected slot-name fold's own definition, at top level. */
	private static boolean isSlotNameKeyDefun(LispVal form) {
		return form instanceof LispCons defun && defun.car() instanceof LispSymbol op
				&& LispNames.DEFUN.equals(op.name()) && defun.cdr() instanceof LispCons name
				&& name.car() instanceof LispSymbol sym && LispNames.SLOT_NAME_KEY.equals(sym.name());
	}

	/** The exact two-argument literal shape {@code (intern <x> :keyword)}. */
	private static boolean isKeywordPackageIntern(LispVal val) {
		return val instanceof LispCons call && call.car() instanceof LispSymbol op && LispNames.INTERN.equals(op.name())
				&& call.cdr() instanceof LispCons nameArg && nameArg.cdr() instanceof LispCons pkgArg
				&& pkgArg.cdr() instanceof LispNil && pkgArg.car() instanceof LispSymbol pkg
				&& ":KEYWORD".equals(pkg.name());
	}

	/**
	 * Any occurrence of one of the names anywhere in the form -- operator position,
	 * argument position, {@code #'} reference, quoted data. Position does not narrow it:
	 * a {@code #'eval} handed to a higher-order function evaluates just as much as a call
	 * does.
	 */
	private static boolean scan(LispVal val) {
		if (val instanceof LispSymbol sym) {
			if (EVALUATES_DATA.contains(sym.name())) {
				report(sym.name());
				return true;
			}
			return false;
		}
		LispVal cur = val;
		while (cur instanceof LispCons cell) {
			if (scan(cell.car())) {
				return true;
			}
			cur = cell.cdr();
		}
		return cur instanceof LispSymbol && scan(cur);
	}

	/**
	 * Names the operator that turned the gate off. The question a user asks when
	 * {@code --optimize} does not shrink a program is "which operator kept every function
	 * dispatchable?", and only the compiler can answer it.
	 */
	private static void report(String name) {
		if (Boolean.getBoolean("rontolisp.debug.dispatchgate")) {
			System.err.println("[dispatch-gate] every function stays dispatchable because of: " + name);
		}
	}

}
