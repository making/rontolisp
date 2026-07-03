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
		// A global shadowed by a lexical binding visible at this lambda's creation site
		// (an enclosing let variable, defun parameter, or an outer closure's capture)
		// must be captured like any other free variable, not resolved to the global.
		Set<String> visibleGlobals = new HashSet<>(ctx.globals);
		visibleGlobals.removeAll(ctx.locals.keySet());
		visibleGlobals.removeAll(ctx.captures.keySet());
		LinkedHashSet<String> freeVars = FreeVarAnalyzer.findFreeVars(bodyExprs, boundVars, ctx.functions.keySet(),
				visibleGlobals);
		int funcId = ctx.nextFuncId[0]++;
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
				if (ctx.boxedVars.contains(freeVar)) {
					ctx.emit(Opcode.ALOAD);
					ctx.emit(slot);
				}
				else {
					ctx.emit(Opcode.ICONST_1);
					ctx.emit(Opcode.ANEWARRAY);
					ctx.emitU2(ctx.objectClass.index());
					ctx.emit(Opcode.DUP);
					ctx.emit(Opcode.ICONST_0);
					ctx.emit(Opcode.ALOAD);
					ctx.emit(slot);
					ctx.emit(Opcode.AASTORE);
				}
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
		int savedNextLocal = ctx.nextLocal;
		for (int i = 0; i < required; i++) {
			JvmExprCompiler.compileExpr(callArgs.get(i + 1), ctx, className);
			int slot = ctx.allocLocal(paramNames.get(i));
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slot);
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
			int restSlot = ctx.allocLocal(paramNames.get(required));
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
		}
		for (int i = 0; i < bodyExprs.size(); i++) {
			if (i > 0) {
				ctx.emit(Opcode.POP);
			}
			JvmExprCompiler.compileExpr(bodyExprs.get(i), ctx, className);
		}
		ctx.locals = savedLocals;
		ctx.nextLocal = savedNextLocal;
	}

}
