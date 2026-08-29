package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.jvm.Opcode;

/**
 * Compiles {@code lambda} expressions (both value creation and inline calls).
 */
final class JvmLambdaCompiler {

	private JvmLambdaCompiler() {
	}

	/**
	 * Compiles a lambda expression as a closure value.
	 */
	static void compileValue(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		LambdaLists.NativeForm nf = LambdaLists.toNative(parts.get(1), parts.subList(2, parts.size()));
		List<String> paramNames = nf.paramNames();
		List<LispVal> bodyExprs = nf.body();
		Set<String> boundVars = new HashSet<>(paramNames);
		// A global, function or built-in name shadowed by a lexical binding visible at
		// this lambda's creation site (an enclosing let variable, defun parameter, or
		// an outer closure's capture) must be captured like any other free variable:
		// Lisp-2 means a bare symbol is always a variable reference.
		Set<String> enclosingLexicals = new HashSet<>(ctx.locals.keySet());
		enclosingLexicals.addAll(ctx.captures.keySet());
		LinkedHashSet<String> freeVars = FreeVarAnalyzer.findFreeVars(bodyExprs, boundVars, ctx.functions.keySet(),
				ctx.globals, enclosingLexicals);
		int funcId = ctx.nextFuncId[0]++;
		// The other place a funcId becomes a callable VALUE (see Ctx.valueFuncIds): a
		// lambda is only ever reached through a dispatcher, so its case must stay.
		ctx.valueFuncIds.add(funcId);
		String methodName = "_lambda_" + funcId;
		ctx.lambdaDecls.add(new JvmLispCompiler.LambdaInfo(funcId, methodName, paramNames, nf.variadic(), bodyExprs,
				new ArrayList<>(freeVars)));
		int totalSize = 1 + freeVars.size();
		JvmEmitHelper.emitIntConst(ctx, totalSize);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		JvmEmitHelper.emitIntConst(ctx, funcId);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.integerValueOf.index());
		ctx.emit(Opcode.AASTORE);
		int captureIdx = 0;
		for (String freeVar : freeVars) {
			ctx.emit(Opcode.DUP);
			JvmEmitHelper.emitIntConst(ctx, 1 + captureIdx);
			Integer slot = ctx.locals.get(freeVar);
			if (slot != null) {
				if (!ctx.boxedVars.contains(freeVar)) {
					// The binder and this emitter must agree on which names need a
					// cell, and ONE owner answers that -- FreeVarAnalyzer's capture
					// walk, which every binder consults. Landing here means the binder
					// was not asked (or answered differently), and what used to happen
					// was silent: the closure got a FRESH one-element cell holding a
					// COPY, so an assignment on either side never reached the other.
					// Two binders were missing the question (a defun nested in a let,
					// an inline ((lambda (p) ...) a) call) and both were wrong answers,
					// not crashes -- so this is loud on purpose.
					throw new IllegalStateException("closure over " + freeVar
							+ " whose binding left it unboxed: the capture analysis and the closure emitter"
							+ " disagree about this name (FreeVarAnalyzer.findCapturedVars)");
				}
				ctx.emit(Opcode.ALOAD);
				ctx.emit(slot);
			}
			else if (ctx.captures.containsKey(freeVar)) {
				ctx.emit(Opcode.ALOAD);
				ctx.emit(ctx.closureEnvSlot);
				JvmEmitHelper.emitIntConst(ctx, 1 + ctx.captures.get(freeVar));
				ctx.emit(Opcode.AALOAD);
			}
			else {
				throw new UnsupportedOperationException("Cannot capture variable: " + freeVar);
			}
			ctx.emit(Opcode.AASTORE);
			captureIdx++;
		}
	}

	/**
	 * Compiles an inline lambda call: {@code ((lambda (params) body) args)}.
	 */
	static void compileCall(LispCons lambda, LispCons call, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> lambdaParts = lambda.toList();
		LambdaLists.NativeForm nf = LambdaLists.toNative(lambdaParts.get(1),
				lambdaParts.subList(2, lambdaParts.size()));
		List<String> paramNames = nf.paramNames();
		List<LispVal> bodyExprs = nf.body();
		List<LispVal> callArgs = call.toList();
		int required = paramNames.size() - (nf.variadic() ? 1 : 0);
		int supplied = callArgs.size() - 1;
		if (supplied < required || (!nf.variadic() && supplied > required)) {
			throw new UnsupportedOperationException("lambda expects " + (nf.variadic() ? "at least " : "") + required
					+ " argument" + (required == 1 ? "" : "s") + ", got " + supplied);
		}
		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);
		Set<String> savedBoxedVars = ctx.boxedVars;
		Map<String, JvmIntFusionCompiler.RawLocal> savedRawLocals = new HashMap<>(ctx.rawLocals);
		Map<String, JvmIntFusionCompiler.LocalIntLambda> savedLocalIntLambdas = new HashMap<>(ctx.localIntLambdas);
		int savedNextLocal = ctx.nextLocal;
		// The parameters are bound HERE, in the caller's frame, so this is a binder and
		// it owes the same question every other binder asks: does a closure in the body
		// read this name? One owner answers it -- FreeVarAnalyzer.findCapturedVars, the
		// same call JvmLetCompiler and the defun/lambda prologues make -- because the
		// closure emitter below decides capture from findFreeVars, and a binder that
		// does not agree with it hands the closure a private snapshot cell instead of
		// the binding (.kb/core-representation.md).
		Set<String> capturedParams = FreeVarAnalyzer.findCapturedVars(bodyExprs, new HashSet<>(paramNames),
				ctx.functions.keySet());
		ctx.boxedVars = new HashSet<>(ctx.boxedVars);
		for (int i = 0; i < required; i++) {
			String name = paramNames.get(i);
			if (capturedParams.contains(name)) {
				ctx.emit(Opcode.ICONST_1);
				ctx.emit(Opcode.ANEWARRAY);
				ctx.emitU2(ctx.objectClass.index());
				ctx.emit(Opcode.DUP);
				ctx.emit(Opcode.ICONST_0);
				JvmExprCompiler.compileExpr(callArgs.get(i + 1), ctx, className);
				ctx.emit(Opcode.AASTORE);
			}
			else {
				JvmExprCompiler.compileExpr(callArgs.get(i + 1), ctx, className);
			}
			int slot = ctx.allocLocal(name);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slot);
			bindName(name, capturedParams.contains(name), ctx);
		}
		if (nf.variadic()) {
			// Evaluate the surplus arguments left to right into temps, then link them
			// into a cons list bound to the rest parameter.
			List<Integer> extraSlots = new ArrayList<>();
			for (int i = required; i < supplied; i++) {
				JvmExprCompiler.compileExpr(callArgs.get(i + 1), ctx, className);
				int s = ctx.allocTemp();
				ctx.emit(Opcode.ASTORE);
				ctx.emit(s);
				extraSlots.add(s);
			}
			String restName = paramNames.get(required);
			int restSlot = ctx.allocLocal(restName);
			ctx.emit(Opcode.ACONST_NULL);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(restSlot);
			for (int k = extraSlots.size() - 1; k >= 0; k--) {
				ctx.emit(Opcode.ICONST_2);
				ctx.emit(Opcode.ANEWARRAY);
				ctx.emitU2(ctx.objectClass.index());
				ctx.emit(Opcode.DUP);
				ctx.emit(Opcode.ICONST_0);
				ctx.emit(Opcode.ALOAD);
				ctx.emit(extraSlots.get(k));
				ctx.emit(Opcode.AASTORE);
				ctx.emit(Opcode.DUP);
				ctx.emit(Opcode.ICONST_1);
				ctx.emit(Opcode.ALOAD);
				ctx.emit(restSlot);
				ctx.emit(Opcode.AASTORE);
				ctx.emit(Opcode.ASTORE);
				ctx.emit(restSlot);
			}
			if (capturedParams.contains(restName)) {
				// The list is built in place, so the cell is wrapped around the
				// finished value rather than around the build.
				ctx.emit(Opcode.ICONST_1);
				ctx.emit(Opcode.ANEWARRAY);
				ctx.emitU2(ctx.objectClass.index());
				ctx.emit(Opcode.DUP);
				ctx.emit(Opcode.ICONST_0);
				ctx.emit(Opcode.ALOAD);
				ctx.emit(restSlot);
				ctx.emit(Opcode.AASTORE);
				ctx.emit(Opcode.ASTORE);
				ctx.emit(restSlot);
			}
			bindName(restName, capturedParams.contains(restName), ctx);
		}
		for (int i = 0; i < bodyExprs.size(); i++) {
			if (i > 0) {
				ctx.emit(Opcode.POP);
			}
			JvmExprCompiler.compileExpr(bodyExprs.get(i), ctx, className);
		}
		ctx.locals = savedLocals;
		ctx.boxedVars = savedBoxedVars;
		ctx.rawLocals = savedRawLocals;
		ctx.localIntLambdas = savedLocalIntLambdas;
		ctx.nextLocal = savedNextLocal;
	}

	/**
	 * Records what representation a freshly bound name has, REPLACING whatever a shadowed
	 * outer binding of the same name had: the maps are keyed on names, so an outer
	 * unboxed dual-representation local or local integer lambda would otherwise still
	 * answer for this one.
	 */
	private static void bindName(String name, boolean boxed, JvmLispCompiler.Ctx ctx) {
		ctx.rawLocals.remove(name);
		ctx.localIntLambdas.remove(name);
		if (boxed) {
			ctx.boxedVars.add(name);
		}
		else {
			ctx.boxedVars.remove(name);
		}
	}

}
