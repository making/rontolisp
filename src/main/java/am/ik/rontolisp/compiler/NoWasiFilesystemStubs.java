package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.macro.LispMacroExpander;

/**
 * Lowers the file-opening forms to call-time error stubs on a {@code --no-wasi} module,
 * which by construction has no filesystem: the module imports nothing, so there is no
 * {@code path_open} behind {@code open} and the call could only trap. The stub turns that
 * trap into the ordinary signaled error the other impossible-here directives already use
 * (the Preview-1 {@code http-handler} policy: a call-time error, never a compile error,
 * because the call may be dead code -- clack's {@code clackup} keeps a
 * {@code (clackup "app.lisp")} branch that a Worker never reaches).
 *
 * <p>
 * The size consequence is the point, not a side effect: dropping the dead bodies drops
 * their {@code read}/{@code eval} occurrences, and those are what hold the
 * funcall-dispatch gate open ({@code RuntimeNameProducers},
 * {@code .kb/optimize-dead-code-elimination.md}) and force the reader/eval runtimes into
 * the module. clack's {@code %load-file} -- {@code (read in nil eof)} +
 * {@code (eval form)} inside a {@code with-open-file} that can never open -- kept every
 * defun of a Worker module dispatchable.
 *
 * <p>
 * What is rewritten: a {@code (with-open-file (var path opts...) body...)} form becomes
 * {@code (progn path (error ...))} -- the path expression keeps its evaluation, matching
 * the real form, whose open runs after the path is computed and before any body -- and an
 * {@code (open path opts...)} call becomes the same shape over all its arguments. Quoted
 * data is left alone, and {@code open} is left alone entirely when the program defines
 * its own {@code (defun open ...)} (the canonical bare {@code OPEN} then names the user
 * function, not CL's).
 *
 * <p>
 * Only a form in OPERATOR position is a call: the walk descends into a list's ELEMENTS,
 * never into its tail as if the tail were itself a form. {@code open} is an ordinary
 * variable name as well as a CL function, and a tail-walking rewrite turns
 * {@code (setq open t)} into a {@code setq} with one argument. What the walk still cannot
 * tell apart is a BINDING from a call -- {@code (let ((open nil)) ...)} and
 * {@code (defun f (open) ...)} spell the binding exactly like {@code (open nil)} spells
 * the call -- so a {@code --no-wasi} program that binds the name that way still gets it
 * stubbed. The failure is loud (a malformed binding the backend rejects), never a wrong
 * module, and the fix is the binding-position knowledge {@code ShadowedBuiltins}'s walker
 * already carries.
 *
 * <p>
 * WASM {@code --no-wasi} only -- the JVM and the WASI-carrying WASM targets have real
 * files, and the interpreter always does. This divergence is per-target fact, not policy
 * drift: it is the same "no filesystem" line {@code .kb/wasm-export-no-wasi.md} already
 * documents, moved from a trap to a diagnosis.
 */
public final class NoWasiFilesystemStubs {

	private NoWasiFilesystemStubs() {
	}

	private static final String STUB_SUFFIX = " requires WASI; a --no-wasi module has no filesystem";

	/**
	 * Rewrites every reachable file-opening form to its call-time stub.
	 * @param program the resolved, flattened top-level forms
	 * @return the rewritten program (the same list when nothing matched)
	 */
	public static List<LispVal> rewrite(List<LispVal> program) {
		boolean userOpen = definesOpen(program);
		List<LispVal> out = new ArrayList<>(program.size());
		boolean changed = false;
		for (LispVal form : program) {
			LispVal rewritten = rewriteForm(form, userOpen);
			changed |= rewritten != form;
			out.add(rewritten);
		}
		return changed ? out : program;
	}

	/** Whether the program defines its own {@code (defun open ...)}. */
	private static boolean definesOpen(List<LispVal> program) {
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& LispNames.DEFUN.equals(head.name()) && cons.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispSymbol name && LispNames.OPEN.equals(name.name())) {
				return true;
			}
		}
		return false;
	}

	private static LispVal rewriteForm(LispVal val, boolean userOpen) {
		if (!(val instanceof LispCons cons)) {
			return val;
		}
		if (cons.car() instanceof LispSymbol head) {
			if (LispNames.QUOTE.equals(head.name())) {
				return cons;
			}
			if (LispNames.WITH_OPEN_FILE.equals(head.name()) && cons.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispCons spec && spec.cdr() instanceof LispCons pathCell) {
				// (with-open-file (var PATH opts...) body...) -> (progn PATH' (error
				// ...)).
				// The real form computes PATH, then opens -- which here can only fail --
				// so the body and the option values never run.
				return SourceProvenance.inherit(cons,
						stub(List.of(rewriteForm(pathCell.car(), userOpen)), head.name()));
			}
			if (!userOpen && LispNames.OPEN.equals(head.name()) && cons.isProperList()) {
				List<LispVal> parts = cons.toList();
				List<LispVal> args = new ArrayList<>(parts.size() - 1);
				for (int i = 1; i < parts.size(); i++) {
					args.add(rewriteForm(parts.get(i), userOpen));
				}
				return SourceProvenance.inherit(cons, stub(args, head.name()));
			}
		}
		// Otherwise walk the ELEMENTS, each of them a form, and never the tail as if it
		// were one: a cdr whose own car is OPEN is an argument list or a binding, not a
		// call. (setq open t) is the case that taught this -- rewriting the tail turned
		// it into (setq (progn t (error ...))), a setq with one argument.
		return rewriteElements(cons, userOpen);
	}

	private static LispVal rewriteElements(LispCons cons, boolean userOpen) {
		LispVal car = rewriteForm(cons.car(), userOpen);
		LispVal cdr = cons.cdr() instanceof LispCons rest ? rewriteElements(rest, userOpen) : cons.cdr();
		return LispCons.rebuilt(cons, car, cdr);
	}

	/** {@code (progn args... (error "OPERATOR requires WASI; ..."))}. */
	private static LispVal stub(List<LispVal> evaluatedArgs, String operator) {
		List<LispVal> progn = new ArrayList<>(evaluatedArgs.size() + 2);
		progn.add(new LispSymbol(LispNames.PROGN));
		progn.addAll(evaluatedArgs);
		progn.add(LispMacroExpander.callTimeUnsupportedStub(operator + STUB_SUFFIX));
		return listToCons(progn);
	}

	private static LispVal listToCons(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

}
