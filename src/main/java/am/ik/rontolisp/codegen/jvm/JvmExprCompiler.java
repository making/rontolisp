package am.ik.rontolisp.codegen.jvm;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Compiles Lisp expressions to JVM bytecode. All methods are static and operate on the
 * compilation context ({@link JvmLispCompiler.Ctx}).
 */
final class JvmExprCompiler {

	private JvmExprCompiler() {
	}

	static void compileExpr(LispVal expr, JvmLispCompiler.Ctx ctx, String className) {
		switch (expr) {
			case LispInteger i -> compileLong(i.value(), ctx);
			case LispDouble d -> compileDouble(d.value(), ctx);
			case LispNil ignored -> ctx.emit(Opcode.ACONST_NULL);
			case LispTrue ignored -> compileLong(1, ctx);
			case LispString s -> compileStringLiteral(s.print(), ctx);
			case LispSymbol sym -> compileSymbolRef(sym, ctx);
			case LispCons cons -> compileCons(cons, ctx, className);
			default -> throw new UnsupportedOperationException("Cannot compile: " + expr.print());
		}
	}

	private static void compileLong(long value, JvmLispCompiler.Ctx ctx) {
		if (value == 0) {
			ctx.emit(Opcode.LCONST_0);
		}
		else if (value == 1) {
			ctx.emit(Opcode.LCONST_1);
		}
		else {
			ConstantPool.LongConstant lc = ctx.cp.addLong(value);
			ctx.emit(Opcode.LDC2_W);
			ctx.emitU2(lc.index());
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.longValueOf.index());
	}

	private static void compileDouble(double value, JvmLispCompiler.Ctx ctx) {
		if (value == 0.0) {
			ctx.emit(Opcode.DCONST_0);
		}
		else if (value == 1.0) {
			ctx.emit(Opcode.DCONST_1);
		}
		else {
			ConstantPool.DoubleConstant dc = ctx.cp.addDouble(value);
			ctx.emit(Opcode.LDC2_W);
			ctx.emitU2(dc.index());
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.doubleValueOf.index());
	}

	private static void compileSymbolRef(LispSymbol sym, JvmLispCompiler.Ctx ctx) {
		String name = sym.name();
		Integer slot = ctx.locals.get(name);
		if (slot != null) {
			if (ctx.boxedVars.contains(name)) {
				ctx.emit(Opcode.ALOAD);
				ctx.emit(slot);
				ctx.emit(Opcode.CHECKCAST);
				ctx.emitU2(ctx.objectArrayClass.index());
				ctx.emit(Opcode.ICONST_0);
				ctx.emit(Opcode.AALOAD);
			}
			else {
				ctx.emit(Opcode.ALOAD);
				ctx.emit(slot);
			}
		}
		else if (ctx.captures.containsKey(name)) {
			int captureIdx = ctx.captures.get(name);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(ctx.closureEnvSlot);
			emitIntConst(ctx, 1 + captureIdx);
			ctx.emit(Opcode.AALOAD);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.AALOAD);
		}
		else if (ctx.functions.containsKey(name)) {
			JvmLispCompiler.FunctionInfo fi = ctx.functions.get(name);
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.ANEWARRAY);
			ctx.emitU2(ctx.objectClass.index());
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_0);
			emitIntConst(ctx, fi.funcId());
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.integerValueOf.index());
			ctx.emit(Opcode.AASTORE);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile symbol reference: " + name);
		}
	}

	private static void compileCons(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		LispVal head = cons.car();
		if (head instanceof LispSymbol sym) {
			switch (sym.name()) {
				case "+" -> compileArith(cons, ctx, Opcode.LADD, Opcode.DADD, className);
				case "-" -> compileArith(cons, ctx, Opcode.LSUB, Opcode.DSUB, className);
				case "*" -> compileArith(cons, ctx, Opcode.LMUL, Opcode.DMUL, className);
				case "/" -> compileArith(cons, ctx, Opcode.LDIV, Opcode.DDIV, className);
				case "mod" -> compileArith(cons, ctx, Opcode.LREM, Opcode.DREM, className);
				case "=" -> compileComparison(cons, ctx, Opcode.IFEQ, className);
				case "<" -> compileComparison(cons, ctx, Opcode.IFLT, className);
				case ">" -> compileComparison(cons, ctx, Opcode.IFGT, className);
				case "<=" -> compileComparison(cons, ctx, Opcode.IFLE, className);
				case ">=" -> compileComparison(cons, ctx, Opcode.IFGE, className);
				case "print" -> compilePrint(cons, ctx, className);
				case "quote" -> compileQuote(cons, ctx, className);
				case "if" -> compileIf(cons, ctx, className);
				case "let" -> compileLet(cons, ctx, className);
				case "progn" -> compileProgn(cons, ctx, className);
				case "setq" -> compileSetq(cons, ctx, className);
				case "lambda" -> compileLambdaValue(cons, ctx, className);
				case "defun" -> ctx.emit(Opcode.ACONST_NULL);
				case "list" -> compileListBuiltin(cons, ctx, className);
				case "car" -> compileCarBuiltin(cons, ctx, className);
				case "cdr" -> compileCdrBuiltin(cons, ctx, className);
				case "cons" -> compileConsBuiltin(cons, ctx, className);
				case "funcall" -> compileFuncallBuiltin(cons, ctx, className);
				case "null" -> compileNullPredicate(cons, ctx, className);
				case "atom" -> compileAtom(cons, ctx, className);
				case "numberp" -> compileNumberp(cons, ctx, className);
				case "integerp" -> compileIntegerp(cons, ctx, className);
				case "floatp" -> compileFloatp(cons, ctx, className);
				case "symbolp" -> compileSymbolp(cons, ctx, className);
				case "stringp" -> compileStringp(cons, ctx, className);
				case "listp" -> compileListp(cons, ctx, className);
				case "consp" -> compileConsp(cons, ctx, className);
				default -> {
					if (ctx.locals.containsKey(sym.name()) || ctx.captures.containsKey(sym.name())) {
						compileIndirectCall(sym.name(), cons, ctx, className);
					}
					else {
						compileFunctionCall(sym.name(), cons, ctx, className);
					}
				}
			}
		}
		else if (head instanceof LispCons headCons && headCons.car() instanceof LispSymbol headSym
				&& "lambda".equals(headSym.name())) {
			compileLambdaCall(headCons, cons, ctx, className);
		}
		else {
			compileGeneralIndirectCall(cons, ctx, className);
		}
	}

	private static void compileArith(LispCons cons, JvmLispCompiler.Ctx ctx, int longOpcode, int doubleOpcode,
			String className) {
		List<LispVal> args = cons.toList();
		if (JvmLispCompiler.hasDoubleLiteral(args)) {
			compileExpr(args.get(1), ctx, className);
			unboxDouble(ctx);
			for (int i = 2; i < args.size(); i++) {
				compileExpr(args.get(i), ctx, className);
				unboxDouble(ctx);
				ctx.emit(doubleOpcode);
			}
			boxDouble(ctx);
		}
		else {
			compileExpr(args.get(1), ctx, className);
			unboxLong(ctx);
			for (int i = 2; i < args.size(); i++) {
				compileExpr(args.get(i), ctx, className);
				unboxLong(ctx);
				ctx.emit(longOpcode);
			}
			boxLong(ctx);
		}
	}

	private static void compileComparison(LispCons cons, JvmLispCompiler.Ctx ctx, int branchOpcode, String className) {
		List<LispVal> args = cons.toList();
		if (JvmLispCompiler.hasDoubleLiteral(args)) {
			compileExpr(args.get(1), ctx, className);
			unboxDouble(ctx);
			compileExpr(args.get(2), ctx, className);
			unboxDouble(ctx);
			ctx.emit(Opcode.DCMPL);
		}
		else {
			compileExpr(args.get(1), ctx, className);
			unboxLong(ctx);
			compileExpr(args.get(2), ctx, className);
			unboxLong(ctx);
			ctx.emit(Opcode.LCMP);
		}
		int ifPos = ctx.code.size();
		ctx.emit(branchOpcode);
		ctx.emitU2(0);
		ctx.emit(Opcode.ACONST_NULL);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		int trueLabel = ctx.code.size();
		patchBranch(ctx, ifPos, trueLabel);
		compileLong(1, ctx);
		int endLabel = ctx.code.size();
		patchBranch(ctx, gotoEndPos, endLabel);
	}

	private static void compilePrint(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(ctx.systemOut.index());
		compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.lispToString.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.printlnStr.index());
		ctx.emit(Opcode.ACONST_NULL);
	}

	private static void compileStringLiteral(String value, JvmLispCompiler.Ctx ctx) {
		ConstantPool.StringConstant sc = ctx.cp.addString(value);
		if (sc.index() <= 255) {
			ctx.emit(Opcode.LDC);
			ctx.emit(sc.index());
		}
		else {
			ctx.emit(Opcode.LDC_W);
			ctx.emitU2(sc.index());
		}
	}

	private static void compileQuote(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		LispVal quoted = ((LispCons) cons.cdr()).car();
		compileQuotedVal(quoted, ctx, className);
	}

	private static void compileQuotedVal(LispVal val, JvmLispCompiler.Ctx ctx, String className) {
		switch (val) {
			case LispInteger i -> compileLong(i.value(), ctx);
			case LispDouble d -> compileDouble(d.value(), ctx);
			case LispNil ignored -> ctx.emit(Opcode.ACONST_NULL);
			case LispTrue ignored -> compileLong(1, ctx);
			case LispString s -> compileStringLiteral(s.print(), ctx);
			case LispSymbol sym -> compileStringLiteral(sym.name(), ctx);
			case LispCons cons -> compileQuotedCons(cons, ctx, className);
			default -> throw new UnsupportedOperationException("Cannot quote: " + val.print());
		}
	}

	private static void compileQuotedCons(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		compileQuotedVal(cons.car(), ctx, className);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_1);
		compileQuotedVal(cons.cdr(), ctx, className);
		ctx.emit(Opcode.AASTORE);
	}

	private static void compileIf(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		compileExpr(parts.get(1), ctx, className);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		compileExpr(parts.get(2), ctx, className);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		int elseStart = ctx.code.size();
		patchBranch(ctx, ifNullPos, elseStart);
		if (parts.size() > 3) {
			compileExpr(parts.get(3), ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		int endPos = ctx.code.size();
		patchBranch(ctx, gotoEndPos, endPos);
	}

	private static void compileLet(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		LispVal bindings = parts.get(1);
		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);
		Set<String> savedBoxedVars = new HashSet<>(ctx.boxedVars);
		int savedNextLocal = ctx.nextLocal;
		Set<String> letVarNames = new HashSet<>();
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons pair = (LispCons) binding;
				letVarNames.add(((LispSymbol) pair.toList().get(0)).name());
			}
		}
		Set<String> capturedInLet = FreeVarAnalyzer.findCapturedVars(parts.subList(2, parts.size()), letVarNames,
				ctx.functions.keySet());
		Set<String> newBoxedVars = new HashSet<>(ctx.boxedVars);
		newBoxedVars.addAll(capturedInLet);
		ctx.boxedVars = newBoxedVars;
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons pair = (LispCons) binding;
				List<LispVal> pairList = pair.toList();
				String name = ((LispSymbol) pairList.get(0)).name();
				if (capturedInLet.contains(name)) {
					ctx.emit(Opcode.ICONST_1);
					ctx.emit(Opcode.ANEWARRAY);
					ctx.emitU2(ctx.objectClass.index());
					ctx.emit(Opcode.DUP);
					ctx.emit(Opcode.ICONST_0);
					compileExpr(pairList.get(1), ctx, className);
					ctx.emit(Opcode.AASTORE);
				}
				else {
					compileExpr(pairList.get(1), ctx, className);
				}
				int slot = ctx.allocLocal(name);
				ctx.emit(Opcode.ASTORE);
				ctx.emit(slot);
			}
		}
		for (int i = 2; i < parts.size(); i++) {
			if (i > 2) {
				ctx.emit(Opcode.POP);
			}
			compileExpr(parts.get(i), ctx, className);
		}
		ctx.locals = savedLocals;
		ctx.boxedVars = savedBoxedVars;
		ctx.nextLocal = savedNextLocal;
	}

	private static void compileProgn(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		for (int i = 1; i < parts.size(); i++) {
			if (i > 1) {
				ctx.emit(Opcode.POP);
			}
			compileExpr(parts.get(i), ctx, className);
		}
	}

	private static void compileSetq(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		String name = ((LispSymbol) parts.get(1)).name();
		compileExpr(parts.get(2), ctx, className);
		Integer slot = ctx.locals.get(name);
		if (slot != null && ctx.boxedVars.contains(name)) {
			int tempSlot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(slot);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
		}
		else if (ctx.captures.containsKey(name)) {
			int captureIdx = ctx.captures.get(name);
			int tempSlot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(ctx.closureEnvSlot);
			emitIntConst(ctx, 1 + captureIdx);
			ctx.emit(Opcode.AALOAD);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
		}
		else {
			ctx.emit(Opcode.DUP);
			if (slot == null) {
				slot = ctx.allocLocal(name);
			}
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slot);
		}
	}

	private static void compileFunctionCall(String name, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.FunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			List<LispVal> args = cons.toList();
			for (int i = 1; i < args.size(); i++) {
				compileExpr(args.get(i), ctx, className);
			}
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(fi.methodref().index());
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + name);
		}
	}

	private static void compileLambdaCall(LispCons lambda, LispCons call, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> lambdaParts = lambda.toList();
		List<String> paramNames = JvmLispCompiler.extractParamNames(lambdaParts.get(1));
		List<LispVal> bodyExprs = lambdaParts.subList(2, lambdaParts.size());
		List<LispVal> callArgs = call.toList();
		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);
		int savedNextLocal = ctx.nextLocal;
		for (int i = 0; i < paramNames.size(); i++) {
			compileExpr(callArgs.get(i + 1), ctx, className);
			int slot = ctx.allocLocal(paramNames.get(i));
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slot);
		}
		for (int i = 0; i < bodyExprs.size(); i++) {
			if (i > 0) {
				ctx.emit(Opcode.POP);
			}
			compileExpr(bodyExprs.get(i), ctx, className);
		}
		ctx.locals = savedLocals;
		ctx.nextLocal = savedNextLocal;
	}

	private static void compileLambdaValue(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		List<String> paramNames = JvmLispCompiler.extractParamNames(parts.get(1));
		List<LispVal> bodyExprs = parts.subList(2, parts.size());
		Set<String> boundVars = new HashSet<>(paramNames);
		LinkedHashSet<String> freeVars = FreeVarAnalyzer.findFreeVars(bodyExprs, boundVars, ctx.functions.keySet());
		int funcId = ctx.nextFuncId[0]++;
		String methodName = "_lambda_" + funcId;
		ctx.lambdaDecls.add(new JvmLispCompiler.LambdaInfo(funcId, methodName, paramNames, bodyExprs,
				new java.util.ArrayList<>(freeVars)));
		int totalSize = 1 + freeVars.size();
		emitIntConst(ctx, totalSize);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		emitIntConst(ctx, funcId);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.integerValueOf.index());
		ctx.emit(Opcode.AASTORE);
		int captureIdx = 0;
		for (String freeVar : freeVars) {
			ctx.emit(Opcode.DUP);
			emitIntConst(ctx, 1 + captureIdx);
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
				emitIntConst(ctx, 1 + ctx.captures.get(freeVar));
				ctx.emit(Opcode.AALOAD);
			}
			else {
				throw new UnsupportedOperationException("Cannot capture variable: " + freeVar);
			}
			ctx.emit(Opcode.AASTORE);
			captureIdx++;
		}
	}

	private static void compileIndirectCall(String name, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int arity = args.size() - 1;
		ctx.indirectCallArities.add(arity);
		compileSymbolRef(new LispSymbol(name), ctx);
		for (int i = 1; i < args.size(); i++) {
			compileExpr(args.get(i), ctx, className);
		}
		emitDispatchCall(arity, ctx, className);
	}

	private static void compileGeneralIndirectCall(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int arity = args.size() - 1;
		ctx.indirectCallArities.add(arity);
		compileExpr(args.get(0), ctx, className);
		for (int i = 1; i < args.size(); i++) {
			compileExpr(args.get(i), ctx, className);
		}
		emitDispatchCall(arity, ctx, className);
	}

	private static void emitDispatchCall(int arity, JvmLispCompiler.Ctx ctx, String className) {
		String dispatchName = "_invoke_" + arity;
		String dispatchDesc = "(" + "Ljava/lang/Object;".repeat(arity + 1) + ")Ljava/lang/Object;";
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(dispatchName);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(dispatchDesc);
		MethodrefConstant methodref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(methodref.index());
	}

	private static void compileListBuiltin(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		ctx.emit(Opcode.ACONST_NULL);
		for (int i = args.size() - 1; i >= 1; i--) {
			int tempSlot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.ICONST_2);
			ctx.emit(Opcode.ANEWARRAY);
			ctx.emitU2(ctx.objectClass.index());
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_0);
			compileExpr(args.get(i), ctx, className);
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.AASTORE);
		}
	}

	private static void compileCarBuiltin(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
	}

	private static void compileCdrBuiltin(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
	}

	private static void compileConsBuiltin(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_1);
		compileExpr(args.get(2), ctx, className);
		ctx.emit(Opcode.AASTORE);
	}

	private static void compileFuncallBuiltin(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int arity = args.size() - 2;
		ctx.indirectCallArities.add(arity);
		compileExpr(args.get(1), ctx, className);
		for (int i = 2; i < args.size(); i++) {
			compileExpr(args.get(i), ctx, className);
		}
		emitDispatchCall(arity, ctx, className);
	}

	/**
	 * Converts an i32 (0=false, non-0=true) on the JVM stack into a Lisp boolean
	 * (null=nil or Long(1)=t).
	 */
	private static void emitBoolFromInt(JvmLispCompiler.Ctx ctx) {
		int ifPos = ctx.code.size();
		ctx.emit(Opcode.IFNE);
		ctx.emitU2(0);
		ctx.emit(Opcode.ACONST_NULL);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		patchBranch(ctx, ifPos, ctx.code.size());
		compileLong(1, ctx);
		patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

	private static void compileNullPredicate(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx, className);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.ACONST_NULL);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		patchBranch(ctx, ifNullPos, ctx.code.size());
		compileLong(1, ctx);
		patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

	private static void compileIntegerp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.longClass.index());
		emitBoolFromInt(ctx);
	}

	private static void compileFloatp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.doubleClass.index());
		emitBoolFromInt(ctx);
	}

	private static void compileNumberp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.numberClass.index());
		emitBoolFromInt(ctx);
	}

	private static void compileConsp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx, className);
		int tempSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.objectArrayClass.index());
		int ifNotArrayPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.integerClass.index());
		int ifFuncRefPos = ctx.code.size();
		ctx.emit(Opcode.IFNE);
		ctx.emitU2(0);
		compileLong(1, ctx);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		patchBranch(ctx, ifNotArrayPos, ctx.code.size());
		patchBranch(ctx, ifFuncRefPos, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
		patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

	private static void compileAtom(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx, className);
		int tempSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.objectArrayClass.index());
		int ifNotArrayPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.integerClass.index());
		int ifFuncRefPos = ctx.code.size();
		ctx.emit(Opcode.IFNE);
		ctx.emitU2(0);
		ctx.emit(Opcode.ACONST_NULL);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		patchBranch(ctx, ifNotArrayPos, ctx.code.size());
		patchBranch(ctx, ifFuncRefPos, ctx.code.size());
		compileLong(1, ctx);
		patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

	private static void compileListp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx, className);
		int tempSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.objectArrayClass.index());
		int ifNotArrayPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.integerClass.index());
		int ifFuncRefPos = ctx.code.size();
		ctx.emit(Opcode.IFNE);
		ctx.emitU2(0);
		patchBranch(ctx, ifNullPos, ctx.code.size());
		compileLong(1, ctx);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		patchBranch(ctx, ifNotArrayPos, ctx.code.size());
		patchBranch(ctx, ifFuncRefPos, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
		patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

	private static void compileStringp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx, className);
		int tempSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.stringClass.index());
		int ifNotStringPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.stringCharAt.index());
		emitIntConst(ctx, 34);
		int ifNotQuotePos = ctx.code.size();
		ctx.emit(Opcode.IF_ICMPNE);
		ctx.emitU2(0);
		compileLong(1, ctx);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		patchBranch(ctx, ifNotStringPos, ctx.code.size());
		patchBranch(ctx, ifNotQuotePos, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
		patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

	private static void compileSymbolp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx, className);
		int tempSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.stringClass.index());
		int ifNotStringPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.stringCharAt.index());
		emitIntConst(ctx, 34);
		int ifQuotePos = ctx.code.size();
		ctx.emit(Opcode.IF_ICMPEQ);
		ctx.emitU2(0);
		compileLong(1, ctx);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		patchBranch(ctx, ifNotStringPos, ctx.code.size());
		patchBranch(ctx, ifQuotePos, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
		patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

	private static void unboxLong(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.longClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.longValue.index());
	}

	private static void boxLong(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.longValueOf.index());
	}

	private static void unboxDouble(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.numberClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.numberDoubleValue.index());
	}

	private static void boxDouble(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.doubleValueOf.index());
	}

	private static void emitIntConst(JvmLispCompiler.Ctx ctx, int value) {
		if (value >= 0 && value <= 5) {
			ctx.emit(Opcode.ICONST_0 + value);
		}
		else if (value >= -128 && value <= 127) {
			ctx.emit(Opcode.BIPUSH);
			ctx.emit(value & 0xFF);
		}
		else {
			ctx.emit(Opcode.SIPUSH);
			ctx.emitU2(value);
		}
	}

	/**
	 * Boxes a local variable in an Object[1] cell for capture-by-reference.
	 */
	static void emitBoxLocal(JvmLispCompiler.Ctx ctx, int slot) {
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slot);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(slot);
	}

	private static void patchBranch(JvmLispCompiler.Ctx ctx, int branchPos, int targetPos) {
		JvmRuntimeBuilder.patchBranch(ctx.code, branchPos, targetPos);
	}

}
