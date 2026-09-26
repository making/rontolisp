package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Rewrites fixed-arity {@code defun}s with more parameters than the WASM callable-type
 * limit ({@link WasmLispCompiler#MAX_CALLABLE_ARITY}) into the "bundle the extra
 * arguments into a list" shape the limit's error message suggests -- automatically, so
 * real-library code with wide helper signatures compiles without source changes. Raising
 * the limit itself would move indices in every module -- it is the origin
 * {@code FUNC_DISPATCH_SPREAD} and every later {@code FUNC_*} and type index are defined
 * off -- so the transform stays at the AST level:
 *
 * <pre>
 * (defun f (p1 .. p12) body)  -> (defun f (&amp;rest %bundle)
 *                                  (let* ((p1 (progn (check %bundle) (nth 0 %bundle)))
 *                                         (p2 (nth 1 %bundle)) .. (p12 (nth 11 %bundle)))
 *                                    body))
 * </pre>
 *
 * The bundle is a {@code &rest} list, so every caller packs it the way it packs any
 * variadic callee's: a direct call, {@code #'f} through a per-arity dispatcher,
 * {@code apply}, and a name the compiled {@code eval} resolves at run time, which reaches
 * the SPREAD dispatcher. A bundle passed as one explicit list argument after the first
 * nine used to serve direct calls only: {@code #'f} was refused and
 * {@code (eval '(f ...))} trapped on the cast of an argument that was no list. With no
 * required parameter left, the check is the only count judge, so every wrong count -- a
 * direct call's included -- signals the interpreter's {@code program-error},
 * {@code Function expects 12 arguments, got 13}; keeping the first nine as required
 * parameters would have made a short call report {@code at least 9}. The price is one
 * cons per argument per call, on a signature only a generated or unusually wide helper
 * has. Variadic ({@code &rest}) definitions past the limit keep the existing hard error.
 */
final class WasmArityBundler {

	private static final String BUNDLE_VAR = "%arity-bundle";

	/** The count check a rewritten defun opens with ({@link #compileCheck}). */
	static final String CHECK = "%ARITY-BUNDLE-CHECK";

	private WasmArityBundler() {
	}

	/**
	 * Applies the transform to every too-wide fixed-arity top-level defun; its call sites
	 * need no rewrite. Runs after lambda-list desugaring (so only the native "required +
	 * &rest" shape appears) and before compilation.
	 * @param program the top-level forms
	 * @return the transformed program (the same list when nothing is too wide)
	 */
	static List<LispVal> bundle(List<LispVal> program) {
		List<LispVal> out = null;
		for (int i = 0; i < program.size(); i++) {
			if (program.get(i) instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& LispNames.DEFUN.equals(op.name()) && cons.isProperList()) {
				List<LispVal> parts = cons.toList();
				if (parts.size() >= 3 && parts.get(1) instanceof LispSymbol
						&& parts.get(2) instanceof LispCons paramsCons) {
					List<LispVal> params = paramsCons.toList();
					if (params.size() > WasmLispCompiler.MAX_CALLABLE_ARITY
							&& params.stream().allMatch(WasmArityBundler::isPlainParam)) {
						if (out == null) {
							out = new ArrayList<>(program);
						}
						out.set(i, bundleDefun(parts));
					}
				}
			}
		}
		return out == null ? program : out;
	}

	private static boolean isPlainParam(LispVal param) {
		return param instanceof LispSymbol sym && !sym.name().startsWith("&");
	}

	/**
	 * The widest PER-ARITY dispatch the program asks for: the argument count of its
	 * widest {@code funcall}, or the list count of its widest
	 * {@code mapcar}/{@code mapc}/{@code mapcan} (each iteration funcalls the mapped
	 * function with one element per list), whichever is larger -- {@code 0} when it has
	 * neither. What the compiler sizes its EXTRA dispatcher tier from
	 * ({@link WasmLispCompiler#callArityCeiling()}), so that an eleven-argument
	 * {@code funcall} answers with one 975-byte ladder rather than by pulling in a 12 KB
	 * spread dispatcher that serves every callable at every width.
	 *
	 * <p>
	 * A pre-scan of the source program cannot see a {@code funcall} a macro synthesizes
	 * later in Pass 2, which is exactly what the codegen ceiling checks are still for:
	 * anything past the ceiling stays a call-time signal ({@code funcall}) or a compile
	 * error (the map family, which has no per-site fallback).
	 * @param program the top-level forms
	 * @return the widest dispatch arity
	 */
	static int widestDispatchArity(List<LispVal> program) {
		int widest = 0;
		for (LispVal form : program) {
			widest = Math.max(widest, widestDispatchArityIn(form));
		}
		return widest;
	}

	private static int widestDispatchArityIn(LispVal form) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()
				|| cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())) {
			return 0;
		}
		List<LispVal> parts = cons.toList();
		// (funcall f a1 .. aN) and (mapcar f l1 .. lN) both dispatch at N.
		int widest = cons.car() instanceof LispSymbol op
				&& (LispNames.FUNCALL.equals(op.name()) || LispNames.MAPCAR.equals(op.name())
						|| LispNames.MAPC.equals(op.name()) || LispNames.MAPCAN.equals(op.name())) ? parts.size() - 2
								: 0;
		for (LispVal part : parts) {
			widest = Math.max(widest, widestDispatchArityIn(part));
		}
		return widest;
	}

	/**
	 * Lowers a {@code funcall} with more arguments than the module's per-arity
	 * dispatchers can take into the equivalent {@code apply}:
	 *
	 * <pre>
	 * (funcall f a1 .. a15) -> (apply f (list a1 .. a15))
	 * </pre>
	 *
	 * The per-arity dispatchers take one WASM parameter per Lisp argument and so stop at
	 * the ceiling, but {@code _apply} does not: it hands the whole argument list to the
	 * SPREAD dispatcher ({@link WasmLispCompiler#FUNC_DISPATCH_SPREAD}), which reads each
	 * target's parameters back out of the list. That mechanism already exists for
	 * {@code apply} through a computed designator; this pass is what lets {@code funcall}
	 * reach it too, instead of compiling to a call-time "not supported" signal.
	 *
	 * <p>
	 * It has to be an AST pass rather than a codegen branch because the injected
	 * {@code apply} is what turns the eval runtime on -- {@code usesEval} is a scan of
	 * the program, and it runs after this one.
	 *
	 * <p>
	 * A keyword lambda list is the shape that reaches the fixed block's limit in
	 * practice: the arguments are passed through verbatim for the callee's own dispatcher
	 * to parse, so chipz's {@code (funcall fun state input output :input-start s
	 * :input-end e :output-start s :output-end e)} is eleven of them for a function whose
	 * lambda list has seven parameters. That one is now inside the derived ceiling and
	 * reaches its own dispatcher; what still comes here is a call wide enough that a
	 * per-arity ladder is the more expensive answer.
	 * @param program the top-level forms
	 * @param ceiling the widest funcall this module dispatches per arity
	 * ({@link WasmLispCompiler#callArityCeiling()})
	 * @return the transformed program (the same list when no funcall is too wide)
	 */
	static List<LispVal> spreadOverArityFuncalls(List<LispVal> program, int ceiling) {
		List<LispVal> out = new ArrayList<>(program.size());
		boolean changed = false;
		for (LispVal form : program) {
			LispVal rewritten = spread(form, ceiling);
			changed |= rewritten != form;
			out.add(rewritten);
		}
		return changed ? out : program;
	}

	private static LispVal spread(LispVal form, int ceiling) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return form;
		}
		boolean quoted = cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name());
		if (quoted) {
			return form;
		}
		List<LispVal> parts = cons.toList();
		List<LispVal> out = new ArrayList<>(parts.size());
		for (LispVal part : parts) {
			out.add(spread(part, ceiling));
		}
		if (cons.car() instanceof LispSymbol op && LispNames.FUNCALL.equals(op.name()) && out.size() - 2 > ceiling) {
			List<LispVal> listParts = new ArrayList<>();
			listParts.add(new LispSymbol(LispNames.LIST));
			listParts.addAll(out.subList(2, out.size()));
			return listToCons(List.of(new LispSymbol(LispNames.APPLY), out.get(1), listToCons(listParts)));
		}
		return LispCons.rebuiltList(cons, out);
	}

	private static LispVal bundleDefun(List<LispVal> parts) {
		List<LispVal> params = ((LispCons) parts.get(2)).toList();
		LispSymbol bundle = new LispSymbol(BUNDLE_VAR);
		List<LispVal> newParams = List.of(new LispSymbol(LispNames.LAMBDA_REST), bundle);
		List<LispVal> letBindings = new ArrayList<>();
		for (int i = 0; i < params.size(); i++) {
			LispVal nth = listToCons(List.of(new LispSymbol(LispNames.NTH), new LispInteger(i), bundle));
			if (i == 0) {
				nth = listToCons(List.of(new LispSymbol(LispNames.PROGN), bundleCheck(bundle, params.size()), nth));
			}
			letBindings.add(listToCons(List.of(params.get(i), nth)));
		}
		List<LispVal> letParts = new ArrayList<>();
		letParts.add(new LispSymbol(LispNames.LET_STAR));
		letParts.add(listToCons(letBindings));
		letParts.addAll(parts.subList(3, parts.size()));
		return listToCons(List.of(parts.get(0), parts.get(1), listToCons(newParams), listToCons(letParts)));
	}

	/**
	 * {@code (%arity-bundle-check bundle n)}: the count check a fixed-arity callee's
	 * dispatch would make ({@link #compileCheck}).
	 */
	private static LispVal bundleCheck(LispSymbol bundle, int arity) {
		return listToCons(List.of(new LispSymbol(CHECK), bundle, new LispInteger(arity)));
	}

	/**
	 * Compiles {@code (%arity-bundle-check bundle n)} to nil, after measuring the list:
	 * where the module reports a wrong count ({@code _arity_chk}, EH mode behind a
	 * handler landing pad) it throws the interpreter's {@code program-error},
	 * {@code Function expects n arguments, got m}, through the helper every other count
	 * report uses; elsewhere a wrong count traps, as a dispatcher's no-match arm does
	 * there. A Lisp-level {@code (error 'program-error ...)} would have pulled the
	 * instance machinery into every module with a wide defun, and could not be compiled
	 * at all where the instance gate had already closed.
	 * @param cons the check form
	 * @param ctx the compilation context
	 */
	static void compileCheck(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		int arity = (int) ((LispInteger) parts.get(2)).value();
		if (ctx.arityChkFuncIndex >= 0) {
			WasmExprCompiler.compileExpr(parts.get(1), ctx);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(WasmRuntimeBuilder.arityShape(arity, false, -1));
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(ctx.arityChkFuncIndex);
			ctx.writer.write(Instruction.DROP);
		}
		else {
			WasmExprCompiler.compileExpr(listToCons(List.of(new LispSymbol(LispNames.LENGTH), parts.get(1))), ctx);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(Type.I31.code());
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(arity);
			ctx.writer.write(Instruction.I32_NE);
			ctx.writer.write(Instruction.IF, 0x40);
			ctx.writer.write(Instruction.UNREACHABLE);
			ctx.writer.write(Instruction.END);
		}
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

	private static LispVal listToCons(List<LispVal> items) {
		LispVal result = LispNil.INSTANCE;
		for (int i = items.size() - 1; i >= 0; i--) {
			result = new LispCons(items.get(i), result);
		}
		return result;
	}

}
