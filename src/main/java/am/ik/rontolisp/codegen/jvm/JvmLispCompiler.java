package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.rontolisp.compiler.LispCompiler;

import am.ik.jvm.AccessFlag;
import am.ik.jvm.ByteCodeWriter;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Compiles Lisp expressions to JVM .class bytecode. Uses class file version 50 (Java 6)
 * to avoid mandatory StackMapTable. Supports first-class functions, closures, and
 * capture-by-reference semantics.
 */
public final class JvmLispCompiler implements LispCompiler {

	private final String className;

	public JvmLispCompiler(String className) {
		this.className = className;
	}

	@Override
	public byte[] compile(List<LispVal> program) {
		ConstantPool cp = new ConstantPool();
		ClassConstant thisClass = cp.addClass(cp.addUtf8(this.className));
		ClassConstant objectClass = cp.addClass(cp.addUtf8("java/lang/Object"));

		ClassConstant systemClass = cp.addClass(cp.addUtf8("java/lang/System"));
		FieldrefConstant systemOut = cp.addFieldref(systemClass,
				cp.addNameAndType(cp.addUtf8("out"), cp.addUtf8("Ljava/io/PrintStream;")));
		ClassConstant printStreamClass = cp.addClass(cp.addUtf8("java/io/PrintStream"));

		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		MethodrefConstant longValueOf = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(J)Ljava/lang/Long;")));
		MethodrefConstant longValue = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("longValue"), cp.addUtf8("()J")));

		MethodrefConstant printlnStr = cp.addMethodref(printStreamClass,
				cp.addNameAndType(cp.addUtf8("println"), cp.addUtf8("(Ljava/lang/String;)V")));

		ClassConstant integerClass = cp.addClass(cp.addUtf8("java/lang/Integer"));
		MethodrefConstant integerValueOf = cp.addMethodref(integerClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(I)Ljava/lang/Integer;")));
		MethodrefConstant integerValue = cp.addMethodref(integerClass,
				cp.addNameAndType(cp.addUtf8("intValue"), cp.addUtf8("()I")));

		Utf8Constant lispToStringName = cp.addUtf8("_lispToString");
		Utf8Constant lispToStringDescUtf = cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/String;");
		MethodrefConstant lispToStringMethod = cp.addMethodref(thisClass,
				cp.addNameAndType(lispToStringName, lispToStringDescUtf));
		Utf8Constant consToStringName = cp.addUtf8("_consToString");
		Utf8Constant consToStringDescUtf = cp.addUtf8("([Ljava/lang/Object;)Ljava/lang/String;");
		MethodrefConstant consToStringMethod = cp.addMethodref(thisClass,
				cp.addNameAndType(consToStringName, consToStringDescUtf));
		ClassConstant stringClass = cp.addClass(cp.addUtf8("java/lang/String"));
		ClassConstant objectArrayClass = cp.addClass(cp.addUtf8("[Ljava/lang/Object;"));
		ClassConstant stringBuilderClass = cp.addClass(cp.addUtf8("java/lang/StringBuilder"));
		MethodrefConstant longToString = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		MethodrefConstant objectToString = cp.addMethodref(objectClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		MethodrefConstant sbInitStr = cp.addMethodref(stringBuilderClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant sbAppendStr = cp.addMethodref(stringBuilderClass,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/StringBuilder;")));
		MethodrefConstant sbToString = cp.addMethodref(stringBuilderClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		ConstantPool.StringConstant nilStr = cp.addString("nil");
		ConstantPool.StringConstant funcStr = cp.addString("#<function>");
		ConstantPool.StringConstant openParenStr = cp.addString("(");
		ConstantPool.StringConstant closeParenStr = cp.addString(")");
		ConstantPool.StringConstant spaceStr = cp.addString(" ");
		ConstantPool.StringConstant dotStr = cp.addString(" . ");

		// Pass 1: Collect defun declarations and top-level expressions
		List<DefunDecl> defuns = new ArrayList<>();
		List<LispVal> topLevelExprs = new ArrayList<>();
		for (LispVal expr : program) {
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym && "defun".equals(sym.name())) {
				List<LispVal> parts = cons.toList();
				String funcName = ((LispSymbol) parts.get(1)).name();
				List<String> paramNames = extractParamNames(parts.get(2));
				defuns.add(new DefunDecl(funcName, paramNames, parts.subList(3, parts.size())));
			}
			else if (isSetqLambda(expr)) {
				defuns.add(extractSetqLambda(expr));
			}
			else {
				topLevelExprs.add(expr);
			}
		}

		// Assign funcIds and register in CP
		int[] nextFuncId = { 0 };
		Map<String, FunctionInfo> functions = new HashMap<>();
		for (DefunDecl defun : defuns) {
			int funcId = nextFuncId[0]++;
			String descriptor = "(" + "Ljava/lang/Object;".repeat(defun.paramNames.size()) + ")Ljava/lang/Object;";
			Utf8Constant nameUtf8 = cp.addUtf8(defun.name);
			Utf8Constant descUtf8 = cp.addUtf8(descriptor);
			MethodrefConstant methodref = cp.addMethodref(thisClass, cp.addNameAndType(nameUtf8, descUtf8));
			functions.put(defun.name,
					new FunctionInfo(funcId, defun.paramNames.size(), false, methodref, nameUtf8, descUtf8));
		}

		// Shared state for lambda discovery
		List<LambdaInfo> lambdaDecls = new ArrayList<>();
		Set<Integer> indirectCallArities = new HashSet<>();

		// Pass 2a: Compile each defun body
		List<Ctx> funcCtxs = new ArrayList<>();
		for (DefunDecl defun : defuns) {
			Ctx funcCtx = createCtx(cp, systemOut, printlnStr, lispToStringMethod, longClass, longValueOf, longValue,
					objectClass, objectArrayClass, integerClass, integerValueOf, integerValue, functions, lambdaDecls,
					indirectCallArities, nextFuncId);
			funcCtx.nextLocal = defun.paramNames.size();
			funcCtx.maxLocals = defun.paramNames.size();
			for (int i = 0; i < defun.paramNames.size(); i++) {
				funcCtx.locals.put(defun.paramNames.get(i), i);
			}
			// Determine which params are captured by nested lambdas
			Set<String> capturedVars = FreeVarAnalyzer.findCapturedVars(defun.bodyExprs,
					new HashSet<>(defun.paramNames), functions.keySet());
			funcCtx.boxedVars = capturedVars;
			// Box captured params
			for (String paramName : defun.paramNames) {
				if (capturedVars.contains(paramName)) {
					Integer slot = funcCtx.locals.get(paramName);
					if (slot != null) {
						emitBoxLocal(funcCtx, slot);
					}
				}
			}
			for (int i = 0; i < defun.bodyExprs.size(); i++) {
				if (i > 0) {
					funcCtx.emit(Opcode.POP);
				}
				compileExpr(defun.bodyExprs.get(i), funcCtx);
			}
			funcCtx.emit(Opcode.ARETURN);
			funcCtxs.add(funcCtx);
		}

		// Pass 2b: Compile top-level expressions as main() body
		Ctx mainCtx = createCtx(cp, systemOut, printlnStr, lispToStringMethod, longClass, longValueOf, longValue,
				objectClass, objectArrayClass, integerClass, integerValueOf, integerValue, functions, lambdaDecls,
				indirectCallArities, nextFuncId);
		for (LispVal expr : topLevelExprs) {
			compileExpr(expr, mainCtx);
			mainCtx.emit(Opcode.POP);
		}
		mainCtx.emit(Opcode.RETURN);

		// Pass 2c: Compile lambda bodies (iteratively, new lambdas may be discovered
		// during defun compilation, top-level compilation, or even lambda compilation)
		List<Ctx> lambdaCtxs = new ArrayList<>();
		List<FunctionInfo> lambdaFuncInfos = new ArrayList<>();
		int lambdaIdx = 0;
		while (lambdaIdx < lambdaDecls.size()) {
			LambdaInfo lambda = lambdaDecls.get(lambdaIdx);
			// Register lambda in CP: first param is Object[] env, rest are lambda params
			String descriptor = "([Ljava/lang/Object;" + "Ljava/lang/Object;".repeat(lambda.paramNames.size())
					+ ")Ljava/lang/Object;";
			Utf8Constant nameUtf8 = cp.addUtf8(lambda.methodName);
			Utf8Constant descUtf8 = cp.addUtf8(descriptor);
			MethodrefConstant methodref = cp.addMethodref(thisClass, cp.addNameAndType(nameUtf8, descUtf8));
			FunctionInfo fi = new FunctionInfo(lambda.funcId, lambda.paramNames.size(), true, methodref, nameUtf8,
					descUtf8);
			lambdaFuncInfos.add(fi);

			Ctx lambdaCtx = createCtx(cp, systemOut, printlnStr, lispToStringMethod, longClass, longValueOf, longValue,
					objectClass, objectArrayClass, integerClass, integerValueOf, integerValue, functions, lambdaDecls,
					indirectCallArities, nextFuncId);
			lambdaCtx.closureEnvSlot = 0; // slot 0 = env Object[]
			// Lambda params start at slot 1
			for (int i = 0; i < lambda.paramNames.size(); i++) {
				lambdaCtx.locals.put(lambda.paramNames.get(i), i + 1);
			}
			lambdaCtx.nextLocal = lambda.paramNames.size() + 1; // +1 for env
			lambdaCtx.maxLocals = lambdaCtx.nextLocal;
			// Set up captures mapping
			Map<String, Integer> captures = new HashMap<>();
			for (int i = 0; i < lambda.freeVarNames.size(); i++) {
				captures.put(lambda.freeVarNames.get(i), i);
			}
			lambdaCtx.captures = captures;
			// Determine which locals are captured by further nested lambdas
			Set<String> lambdaLocalVars = new HashSet<>(lambda.paramNames);
			Set<String> capturedVars = FreeVarAnalyzer.findCapturedVars(lambda.bodyExprs, lambdaLocalVars,
					functions.keySet());
			lambdaCtx.boxedVars = capturedVars;
			// Box captured params of this lambda
			for (String paramName : lambda.paramNames) {
				if (capturedVars.contains(paramName)) {
					Integer slot = lambdaCtx.locals.get(paramName);
					if (slot != null) {
						emitBoxLocal(lambdaCtx, slot);
					}
				}
			}
			for (int i = 0; i < lambda.bodyExprs.size(); i++) {
				if (i > 0) {
					lambdaCtx.emit(Opcode.POP);
				}
				compileExpr(lambda.bodyExprs.get(i), lambdaCtx);
			}
			lambdaCtx.emit(Opcode.ARETURN);
			lambdaCtxs.add(lambdaCtx);
			lambdaIdx++;
		}

		// Build dispatch functions for each needed arity
		List<DispatchMethod> dispatchMethods = new ArrayList<>();
		for (int arity : indirectCallArities) {
			DispatchMethod dm = buildDispatchMethod(arity, functions, lambdaDecls, lambdaFuncInfos, cp, thisClass,
					objectArrayClass, integerClass, integerValue);
			dispatchMethods.add(dm);
		}

		// Build _lispToString and _consToString helper method bodies
		List<Integer> ltsCode = buildLispToStringBody(longClass, stringClass, objectArrayClass, integerClass,
				longToString, objectToString, consToStringMethod, nilStr, funcStr);
		List<Integer> ctsCode = buildConsToStringBody(objectArrayClass, stringBuilderClass, sbInitStr, sbAppendStr,
				sbToString, lispToStringMethod, openParenStr, closeParenStr, spaceStr, dotStr);

		Utf8Constant mainUtf8 = cp.addUtf8("main");
		Utf8Constant mainDesc = cp.addUtf8("([Ljava/lang/String;)V");
		Utf8Constant codeUtf8 = cp.addUtf8("Code");

		ByteArrayOutputStream classOut = new ByteArrayOutputStream();
		new ByteCodeWriter(classOut) //
			.write(0xCA, 0xFE, 0xBA, 0xBE) //
			.writeVersion(0, 50) //
			.writeConstantPool(cp) //
			.writeClass(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_SUPER, thisClass, objectClass) //
			.writeInterfaces(i -> {
			})
			.writeFields(f -> {
			})
			.writeMethods(methods -> {
				methods.add(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, mainUtf8, mainDesc,
						method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(mainCtx.maxStack)
								.writeU2(mainCtx.maxLocals)
								.writeCode((Object[]) mainCtx.code.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
				for (int i = 0; i < defuns.size(); i++) {
					FunctionInfo fi = java.util.Objects.requireNonNull(functions.get(defuns.get(i).name));
					final Ctx funcCtx = funcCtxs.get(i);
					methods.add(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, fi.nameUtf8, fi.descUtf8,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(funcCtx.maxStack)
									.writeU2(funcCtx.maxLocals)
									.writeCode((Object[]) funcCtx.code.toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				for (int i = 0; i < lambdaCtxs.size(); i++) {
					FunctionInfo fi = lambdaFuncInfos.get(i);
					final Ctx lambdaCtx = lambdaCtxs.get(i);
					methods.add(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, fi.nameUtf8, fi.descUtf8,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(lambdaCtx.maxStack)
									.writeU2(lambdaCtx.maxLocals)
									.writeCode((Object[]) lambdaCtx.code.toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				for (DispatchMethod dm : dispatchMethods) {
					methods.add(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, dm.nameUtf8, dm.descUtf8,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(64)
									.writeU2(dm.maxLocals)
									.writeCode((Object[]) dm.code.toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, lispToStringName, lispToStringDescUtf,
						method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(2)
								.writeU2(2)
								.writeCode((Object[]) ltsCode.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
				methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, consToStringName, consToStringDescUtf,
						method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(3)
								.writeU2(5)
								.writeCode((Object[]) ctsCode.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
			}) //
			.writeAttributes(a -> {
			});
		return classOut.toByteArray();
	}

	private Ctx createCtx(ConstantPool cp, FieldrefConstant systemOut, MethodrefConstant printlnStr,
			MethodrefConstant lispToString, ClassConstant longClass, MethodrefConstant longValueOf,
			MethodrefConstant longValue, ClassConstant objectClass, ClassConstant objectArrayClass,
			ClassConstant integerClass, MethodrefConstant integerValueOf, MethodrefConstant integerValue,
			Map<String, FunctionInfo> functions, List<LambdaInfo> lambdaDecls, Set<Integer> indirectCallArities,
			int[] nextFuncId) {
		Ctx ctx = new Ctx(cp, systemOut, printlnStr, lispToString, longClass, longValueOf, longValue, objectClass,
				objectArrayClass, integerClass, integerValueOf, integerValue);
		ctx.functions = functions;
		ctx.lambdaDecls = lambdaDecls;
		ctx.indirectCallArities = indirectCallArities;
		ctx.nextFuncId = nextFuncId;
		return ctx;
	}

	private void compileExpr(LispVal expr, Ctx ctx) {
		switch (expr) {
			case LispInteger i -> compileLong(i.value(), ctx);
			case LispNil ignored -> ctx.emit(Opcode.ACONST_NULL);
			case LispTrue ignored -> compileLong(1, ctx);
			case LispString s -> compileStringLiteral(s.print(), ctx);
			case LispSymbol sym -> compileSymbolRef(sym, ctx);
			case LispCons cons -> compileCons(cons, ctx);
			default -> throw new UnsupportedOperationException("Cannot compile: " + expr.print());
		}
	}

	private void compileLong(long value, Ctx ctx) {
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

	private void compileSymbolRef(LispSymbol sym, Ctx ctx) {
		String name = sym.name();
		Integer slot = ctx.locals.get(name);
		if (slot != null) {
			if (ctx.boxedVars.contains(name)) {
				// Read boxed var: load cell, cast, read [0]
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
			// Load cell from closure env: env[1 + captureIdx]
			ctx.emit(Opcode.ALOAD);
			ctx.emit(ctx.closureEnvSlot);
			emitIntConst(ctx, 1 + captureIdx);
			ctx.emit(Opcode.AALOAD);
			// Read value from cell
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.AALOAD);
		}
		else if (ctx.functions.containsKey(name)) {
			// Create function reference: Object[] { Integer(funcId) }
			FunctionInfo fi = ctx.functions.get(name);
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.ANEWARRAY);
			ctx.emitU2(ctx.objectClass.index());
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_0);
			emitIntConst(ctx, fi.funcId);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.integerValueOf.index());
			ctx.emit(Opcode.AASTORE);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile symbol reference: " + name);
		}
	}

	private void compileCons(LispCons cons, Ctx ctx) {
		LispVal head = cons.car();
		if (head instanceof LispSymbol sym) {
			switch (sym.name()) {
				case "+" -> compileArith(cons, ctx, Opcode.LADD);
				case "-" -> compileArith(cons, ctx, Opcode.LSUB);
				case "*" -> compileArith(cons, ctx, Opcode.LMUL);
				case "/" -> compileArith(cons, ctx, Opcode.LDIV);
				case "mod" -> compileArith(cons, ctx, Opcode.LREM);
				case "=" -> compileComparison(cons, ctx, Opcode.IFEQ);
				case "<" -> compileComparison(cons, ctx, Opcode.IFLT);
				case ">" -> compileComparison(cons, ctx, Opcode.IFGT);
				case "<=" -> compileComparison(cons, ctx, Opcode.IFLE);
				case ">=" -> compileComparison(cons, ctx, Opcode.IFGE);
				case "print" -> compilePrint(cons, ctx);
				case "quote" -> compileQuote(cons, ctx);
				case "if" -> compileIf(cons, ctx);
				case "let" -> compileLet(cons, ctx);
				case "progn" -> compileProgn(cons, ctx);
				case "setq" -> compileSetq(cons, ctx);
				case "lambda" -> compileLambdaValue(cons, ctx);
				case "defun" -> ctx.emit(Opcode.ACONST_NULL);
				case "list" -> compileListBuiltin(cons, ctx);
				case "car" -> compileCarBuiltin(cons, ctx);
				case "cdr" -> compileCdrBuiltin(cons, ctx);
				case "cons" -> compileConsBuiltin(cons, ctx);
				case "funcall" -> compileFuncallBuiltin(cons, ctx);
				default -> {
					if (ctx.locals.containsKey(sym.name()) || ctx.captures.containsKey(sym.name())) {
						compileIndirectCall(sym.name(), cons, ctx);
					}
					else {
						compileFunctionCall(sym.name(), cons, ctx);
					}
				}
			}
		}
		else if (head instanceof LispCons headCons && headCons.car() instanceof LispSymbol headSym
				&& "lambda".equals(headSym.name())) {
			compileLambdaCall(headCons, cons, ctx);
		}
		else {
			// Head is a general expression (e.g., (if ...) result called as function)
			compileGeneralIndirectCall(cons, ctx);
		}
	}

	private void compileArith(LispCons cons, Ctx ctx, int opcode) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		unboxLong(ctx);
		for (int i = 2; i < args.size(); i++) {
			compileExpr(args.get(i), ctx);
			unboxLong(ctx);
			ctx.emit(opcode);
		}
		boxLong(ctx);
	}

	private void compileComparison(LispCons cons, Ctx ctx, int branchOpcode) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		unboxLong(ctx);
		compileExpr(args.get(2), ctx);
		unboxLong(ctx);
		ctx.emit(Opcode.LCMP);
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

	private void compilePrint(LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(ctx.systemOut.index());
		compileExpr(args.get(1), ctx);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.lispToString.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.printlnStr.index());
		ctx.emit(Opcode.ACONST_NULL);
	}

	private void compileStringLiteral(String value, Ctx ctx) {
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

	private void compileQuote(LispCons cons, Ctx ctx) {
		LispVal quoted = ((LispCons) cons.cdr()).car();
		compileQuotedVal(quoted, ctx);
	}

	private void compileQuotedVal(LispVal val, Ctx ctx) {
		switch (val) {
			case LispInteger i -> compileLong(i.value(), ctx);
			case LispNil ignored -> ctx.emit(Opcode.ACONST_NULL);
			case LispTrue ignored -> compileLong(1, ctx);
			case LispString s -> compileStringLiteral(s.print(), ctx);
			case LispSymbol sym -> compileStringLiteral(sym.name(), ctx);
			case LispCons cons -> compileQuotedCons(cons, ctx);
			default -> throw new UnsupportedOperationException("Cannot quote: " + val.print());
		}
	}

	private void compileQuotedCons(LispCons cons, Ctx ctx) {
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		compileQuotedVal(cons.car(), ctx);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_1);
		compileQuotedVal(cons.cdr(), ctx);
		ctx.emit(Opcode.AASTORE);
	}

	private void compileIf(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		compileExpr(parts.get(1), ctx);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		compileExpr(parts.get(2), ctx);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		int elseStart = ctx.code.size();
		patchBranch(ctx, ifNullPos, elseStart);
		if (parts.size() > 3) {
			compileExpr(parts.get(3), ctx);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		int endPos = ctx.code.size();
		patchBranch(ctx, gotoEndPos, endPos);
	}

	private void compileLet(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		LispVal bindings = parts.get(1);
		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);
		Set<String> savedBoxedVars = new HashSet<>(ctx.boxedVars);
		int savedNextLocal = ctx.nextLocal;
		// Determine which let bindings are captured by nested lambdas in the body
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
					// Create boxed cell: new Object[1] { value }
					ctx.emit(Opcode.ICONST_1);
					ctx.emit(Opcode.ANEWARRAY);
					ctx.emitU2(ctx.objectClass.index());
					ctx.emit(Opcode.DUP);
					ctx.emit(Opcode.ICONST_0);
					compileExpr(pairList.get(1), ctx);
					ctx.emit(Opcode.AASTORE);
				}
				else {
					compileExpr(pairList.get(1), ctx);
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
			compileExpr(parts.get(i), ctx);
		}
		ctx.locals = savedLocals;
		ctx.boxedVars = savedBoxedVars;
		ctx.nextLocal = savedNextLocal;
	}

	private void compileProgn(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		for (int i = 1; i < parts.size(); i++) {
			if (i > 1) {
				ctx.emit(Opcode.POP);
			}
			compileExpr(parts.get(i), ctx);
		}
	}

	private void compileSetq(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		String name = ((LispSymbol) parts.get(1)).name();
		compileExpr(parts.get(2), ctx);
		Integer slot = ctx.locals.get(name);
		if (slot != null && ctx.boxedVars.contains(name)) {
			// Write to boxed var: cell[0] = value
			// Stack: [value]. We need to store into the cell AND leave value on stack.
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
			// Write to captured var: env[1+idx][0] = value
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
			// Plain local variable
			ctx.emit(Opcode.DUP);
			if (slot == null) {
				slot = ctx.allocLocal(name);
			}
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slot);
		}
	}

	private void compileFunctionCall(String name, LispCons cons, Ctx ctx) {
		FunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			List<LispVal> args = cons.toList();
			for (int i = 1; i < args.size(); i++) {
				compileExpr(args.get(i), ctx);
			}
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(fi.methodref.index());
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + name);
		}
	}

	private void compileLambdaCall(LispCons lambda, LispCons call, Ctx ctx) {
		List<LispVal> lambdaParts = lambda.toList();
		List<String> paramNames = extractParamNames(lambdaParts.get(1));
		List<LispVal> bodyExprs = lambdaParts.subList(2, lambdaParts.size());
		List<LispVal> callArgs = call.toList();
		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);
		int savedNextLocal = ctx.nextLocal;
		for (int i = 0; i < paramNames.size(); i++) {
			compileExpr(callArgs.get(i + 1), ctx);
			int slot = ctx.allocLocal(paramNames.get(i));
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slot);
		}
		for (int i = 0; i < bodyExprs.size(); i++) {
			if (i > 0) {
				ctx.emit(Opcode.POP);
			}
			compileExpr(bodyExprs.get(i), ctx);
		}
		ctx.locals = savedLocals;
		ctx.nextLocal = savedNextLocal;
	}

	private void compileLambdaValue(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		List<String> paramNames = extractParamNames(parts.get(1));
		List<LispVal> bodyExprs = parts.subList(2, parts.size());
		// Find free variables
		Set<String> boundVars = new HashSet<>(paramNames);
		LinkedHashSet<String> freeVars = FreeVarAnalyzer.findFreeVars(bodyExprs, boundVars, ctx.functions.keySet());
		int funcId = ctx.nextFuncId[0]++;
		String methodName = "_lambda_" + funcId;
		ctx.lambdaDecls.add(new LambdaInfo(funcId, methodName, paramNames, bodyExprs, new ArrayList<>(freeVars)));
		// Emit code to create funcval: Object[1 + numCaptures]
		int totalSize = 1 + freeVars.size();
		emitIntConst(ctx, totalSize);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		// [0] = Integer(funcId)
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		emitIntConst(ctx, funcId);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.integerValueOf.index());
		ctx.emit(Opcode.AASTORE);
		// [1..] = captured variable cells
		int captureIdx = 0;
		for (String freeVar : freeVars) {
			ctx.emit(Opcode.DUP);
			emitIntConst(ctx, 1 + captureIdx);
			// Load the cell for this variable
			Integer slot = ctx.locals.get(freeVar);
			if (slot != null) {
				if (ctx.boxedVars.contains(freeVar)) {
					// Already a cell (Object[1])
					ctx.emit(Opcode.ALOAD);
					ctx.emit(slot);
				}
				else {
					// Not boxed yet - wrap in a cell
					// This shouldn't normally happen if findCapturedVars is correct,
					// but handle gracefully
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
				// Load cell from outer closure env
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

	private void compileIndirectCall(String name, LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		int arity = args.size() - 1;
		ctx.indirectCallArities.add(arity);
		// Load funcval
		compileSymbolRef(new LispSymbol(name), ctx);
		// But wait - compileSymbolRef for a boxed var will unbox the cell.
		// For a non-boxed local, it loads the value directly.
		// For a function ref, it creates the funcval. All correct.
		// Evaluate arguments
		for (int i = 1; i < args.size(); i++) {
			compileExpr(args.get(i), ctx);
		}
		// Call dispatch
		emitDispatchCall(arity, ctx);
	}

	private void compileGeneralIndirectCall(LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		int arity = args.size() - 1;
		ctx.indirectCallArities.add(arity);
		// Evaluate head expression to get funcval
		compileExpr(args.get(0), ctx);
		// Evaluate arguments
		for (int i = 1; i < args.size(); i++) {
			compileExpr(args.get(i), ctx);
		}
		emitDispatchCall(arity, ctx);
	}

	private void emitDispatchCall(int arity, Ctx ctx) {
		String dispatchName = "_invoke_" + arity;
		String dispatchDesc = "(" + "Ljava/lang/Object;".repeat(arity + 1) + ")Ljava/lang/Object;";
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(dispatchName);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(dispatchDesc);
		MethodrefConstant methodref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(this.className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(methodref.index());
	}

	private void compileListBuiltin(LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		// Build cons list from right to left
		ctx.emit(Opcode.ACONST_NULL); // nil
		for (int i = args.size() - 1; i >= 1; i--) {
			// Stack: [cdr]
			int tempSlot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.ICONST_2);
			ctx.emit(Opcode.ANEWARRAY);
			ctx.emitU2(ctx.objectClass.index());
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_0);
			compileExpr(args.get(i), ctx);
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.AASTORE);
		}
	}

	private void compileCarBuiltin(LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
	}

	private void compileCdrBuiltin(LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
	}

	private void compileConsBuiltin(LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		compileExpr(args.get(1), ctx);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_1);
		compileExpr(args.get(2), ctx);
		ctx.emit(Opcode.AASTORE);
	}

	private void compileFuncallBuiltin(LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		int arity = args.size() - 2; // subtract funcall and function arg
		ctx.indirectCallArities.add(arity);
		// Evaluate function
		compileExpr(args.get(1), ctx);
		// Evaluate arguments
		for (int i = 2; i < args.size(); i++) {
			compileExpr(args.get(i), ctx);
		}
		emitDispatchCall(arity, ctx);
	}

	private void unboxLong(Ctx ctx) {
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.longClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.longValue.index());
	}

	private void boxLong(Ctx ctx) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.longValueOf.index());
	}

	private void emitIntConst(Ctx ctx, int value) {
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
	private void emitBoxLocal(Ctx ctx, int slot) {
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

	private void patchBranch(Ctx ctx, int branchPos, int targetPos) {
		patchBranch(ctx.code, branchPos, targetPos);
	}

	private static void patchBranch(List<Integer> code, int branchPos, int targetPos) {
		int offset = targetPos - branchPos;
		byte[] bytes = ByteBuffer.allocate(2).putShort((short) offset).array();
		code.set(branchPos + 1, (int) bytes[0]);
		code.set(branchPos + 2, (int) bytes[1]);
	}

	private static void emitU2(List<Integer> code, int value) {
		byte[] bytes = ByteBuffer.allocate(2).putShort((short) value).array();
		code.add((int) bytes[0]);
		code.add((int) bytes[1]);
	}

	private static void emitLdc(List<Integer> code, int cpIndex) {
		if (cpIndex <= 255) {
			code.add(Opcode.LDC);
			code.add(cpIndex);
		}
		else {
			code.add(Opcode.LDC_W);
			emitU2(code, cpIndex);
		}
	}

	private static void emitIntConstStatic(List<Integer> code, int value) {
		if (value >= 0 && value <= 5) {
			code.add(Opcode.ICONST_0 + value);
		}
		else if (value >= -128 && value <= 127) {
			code.add(Opcode.BIPUSH);
			code.add(value & 0xFF);
		}
		else {
			code.add(Opcode.SIPUSH);
			emitU2(code, value);
		}
	}

	private DispatchMethod buildDispatchMethod(int arity, Map<String, FunctionInfo> functions,
			List<LambdaInfo> lambdaDecls, List<FunctionInfo> lambdaFuncInfos, ConstantPool cp, ClassConstant thisClass,
			ClassConstant objectArrayClass, ClassConstant integerClass, MethodrefConstant integerValue) {
		String name = "_invoke_" + arity;
		// Descriptor: (Object funcval, Object a0, ..., Object aN-1) -> Object
		String desc = "(" + "Ljava/lang/Object;".repeat(arity + 1) + ")Ljava/lang/Object;";
		Utf8Constant nameUtf8 = cp.addUtf8(name);
		Utf8Constant descUtf8 = cp.addUtf8(desc);
		// Params: slot 0=funcval, slot 1..arity=args
		// Extra locals: fvSlot=arity+1 (Object[] fv), idSlot=arity+2 (int id)
		int fvSlot = arity + 1;
		int idSlot = arity + 2;
		int maxLocals = arity + 3;
		List<Integer> code = new ArrayList<>();
		// Object[] fv = (Object[]) funcval;
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(Opcode.ASTORE);
		code.add(fvSlot);
		// int id = ((Integer) fv[0]).intValue();
		code.add(Opcode.ALOAD);
		code.add(fvSlot);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		code.add(Opcode.CHECKCAST);
		emitU2(code, integerClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, integerValue.index());
		code.add(Opcode.ISTORE);
		code.add(idSlot);
		// Generate if-else chain for each function with matching arity
		// Named functions (non-closure)
		for (Map.Entry<String, FunctionInfo> entry : functions.entrySet()) {
			FunctionInfo fi = entry.getValue();
			if (fi.paramCount == arity && !fi.isClosure) {
				code.add(Opcode.ILOAD);
				code.add(idSlot);
				emitIntConstStatic(code, fi.funcId);
				int ifPos = code.size();
				code.add(Opcode.IF_ICMPNE);
				emitU2(code, 0);
				// Load args and call
				for (int i = 0; i < arity; i++) {
					code.add(Opcode.ALOAD);
					code.add(i + 1);
				}
				code.add(Opcode.INVOKESTATIC);
				emitU2(code, fi.methodref.index());
				code.add(Opcode.ARETURN);
				patchBranch(code, ifPos, code.size());
			}
		}
		// Lambda functions (closure)
		for (int i = 0; i < lambdaDecls.size(); i++) {
			LambdaInfo lambda = lambdaDecls.get(i);
			FunctionInfo fi = lambdaFuncInfos.get(i);
			if (lambda.paramNames.size() == arity) {
				code.add(Opcode.ILOAD);
				code.add(idSlot);
				emitIntConstStatic(code, fi.funcId);
				int ifPos = code.size();
				code.add(Opcode.IF_ICMPNE);
				emitU2(code, 0);
				// Load fv (closure env), then args
				code.add(Opcode.ALOAD);
				code.add(fvSlot);
				for (int j = 0; j < arity; j++) {
					code.add(Opcode.ALOAD);
					code.add(j + 1);
				}
				code.add(Opcode.INVOKESTATIC);
				emitU2(code, fi.methodref.index());
				code.add(Opcode.ARETURN);
				patchBranch(code, ifPos, code.size());
			}
		}
		// Default: return null
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		return new DispatchMethod(nameUtf8, descUtf8, code, maxLocals);
	}

	/**
	 * Builds bytecode for _lispToString. Now also handles function values (Object[] with
	 * Integer at [0]).
	 */
	private static List<Integer> buildLispToStringBody(ClassConstant longClass, ClassConstant stringClass,
			ClassConstant objectArrayClass, ClassConstant integerClass, MethodrefConstant longToString,
			MethodrefConstant objectToString, MethodrefConstant consToStringMethod, ConstantPool.StringConstant nilStr,
			ConstantPool.StringConstant funcStr) {
		List<Integer> code = new ArrayList<>();
		// if (val == null) return "nil";
		code.add(Opcode.ALOAD_0);
		int ifNonnullPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		emitLdc(code, nilStr.index());
		code.add(Opcode.ARETURN);

		// if (val instanceof Long) return ((Long)val).toString();
		patchBranch(code, ifNonnullPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, longClass.index());
		int ifNotLongPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, longClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, longToString.index());
		code.add(Opcode.ARETURN);

		// if (val instanceof String) return (String)val;
		patchBranch(code, ifNotLongPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, stringClass.index());
		int ifNotStringPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, stringClass.index());
		code.add(Opcode.ARETURN);

		// if (val instanceof Object[])
		patchBranch(code, ifNotStringPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, objectArrayClass.index());
		int ifNotArrayPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		// Cast to Object[] and store in slot 1
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(Opcode.ASTORE_1);
		// Check if arr.length > 0 && arr[0] instanceof Integer -> function value
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ARRAYLENGTH);
		int ifEmptyPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, integerClass.index());
		int ifNotFuncPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		// It's a function value
		emitLdc(code, funcStr.index());
		code.add(Opcode.ARETURN);
		// Not a function -> cons list
		patchBranch(code, ifEmptyPos, code.size());
		patchBranch(code, ifNotFuncPos, code.size());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, consToStringMethod.index());
		code.add(Opcode.ARETURN);

		// return val.toString();
		patchBranch(code, ifNotArrayPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, objectToString.index());
		code.add(Opcode.ARETURN);

		return code;
	}

	private static List<Integer> buildConsToStringBody(ClassConstant objectArrayClass, ClassConstant stringBuilderClass,
			MethodrefConstant sbInitStr, MethodrefConstant sbAppendStr, MethodrefConstant sbToString,
			MethodrefConstant lispToStringMethod, ConstantPool.StringConstant openParenStr,
			ConstantPool.StringConstant closeParenStr, ConstantPool.StringConstant spaceStr,
			ConstantPool.StringConstant dotStr) {
		List<Integer> code = new ArrayList<>();
		code.add(Opcode.NEW);
		emitU2(code, stringBuilderClass.index());
		code.add(Opcode.DUP);
		emitLdc(code, openParenStr.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, sbInitStr.index());
		code.add(Opcode.ASTORE_1);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ASTORE_2);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ISTORE_3);
		int loopStart = code.size();
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, objectArrayClass.index());
		int ifNotArrayPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(Opcode.ASTORE);
		code.add(4);
		code.add(Opcode.ILOAD_3);
		int ifFirstPos = code.size();
		code.add(Opcode.IFNE);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		emitLdc(code, spaceStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
		patchBranch(code, ifFirstPos, code.size());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ALOAD);
		code.add(4);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, lispToStringMethod.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
		code.add(Opcode.ALOAD);
		code.add(4);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.AALOAD);
		code.add(Opcode.ASTORE_2);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.ISTORE_3);
		int gotoPos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, gotoPos, loopStart);
		patchBranch(code, ifNotArrayPos, code.size());
		code.add(Opcode.ALOAD_2);
		int ifNullPos = code.size();
		code.add(Opcode.IFNULL);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		emitLdc(code, dotStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, lispToStringMethod.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
		patchBranch(code, ifNullPos, code.size());
		code.add(Opcode.ALOAD_1);
		emitLdc(code, closeParenStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbToString.index());
		code.add(Opcode.ARETURN);
		return code;
	}

	private static List<String> extractParamNames(LispVal paramsVal) {
		if (paramsVal instanceof LispNil) {
			return List.of();
		}
		return ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
	}

	private static boolean isSetqLambda(LispVal expr) {
		if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym && "setq".equals(sym.name())) {
			List<LispVal> parts = cons.toList();
			if (parts.size() == 3 && parts.get(1) instanceof LispSymbol && parts.get(2) instanceof LispCons valueCons
					&& valueCons.car() instanceof LispSymbol lambdaSym && "lambda".equals(lambdaSym.name())) {
				return true;
			}
		}
		return false;
	}

	private static DefunDecl extractSetqLambda(LispVal expr) {
		List<LispVal> parts = ((LispCons) expr).toList();
		String funcName = ((LispSymbol) parts.get(1)).name();
		List<LispVal> lambdaParts = ((LispCons) parts.get(2)).toList();
		List<String> paramNames = extractParamNames(lambdaParts.get(1));
		return new DefunDecl(funcName, paramNames, lambdaParts.subList(2, lambdaParts.size()));
	}

	private record DefunDecl(String name, List<String> paramNames, List<LispVal> bodyExprs) {
	}

	private record FunctionInfo(int funcId, int paramCount, boolean isClosure, MethodrefConstant methodref,
			Utf8Constant nameUtf8, Utf8Constant descUtf8) {
	}

	private record LambdaInfo(int funcId, String methodName, List<String> paramNames, List<LispVal> bodyExprs,
			List<String> freeVarNames) {
	}

	private record DispatchMethod(Utf8Constant nameUtf8, Utf8Constant descUtf8, List<Integer> code, int maxLocals) {
	}

	private static final class Ctx {

		final ConstantPool cp;

		final FieldrefConstant systemOut;

		final MethodrefConstant printlnStr;

		final MethodrefConstant lispToString;

		final ClassConstant longClass;

		final MethodrefConstant longValueOf;

		final MethodrefConstant longValue;

		final ClassConstant objectClass;

		final ClassConstant objectArrayClass;

		final ClassConstant integerClass;

		final MethodrefConstant integerValueOf;

		final MethodrefConstant integerValue;

		final List<Integer> code = new ArrayList<>();

		Map<String, Integer> locals = new HashMap<>();

		Map<String, FunctionInfo> functions = Map.of();

		Map<String, Integer> captures = Map.of();

		Set<String> boxedVars = Set.of();

		int closureEnvSlot = -1;

		List<LambdaInfo> lambdaDecls = new ArrayList<>();

		Set<Integer> indirectCallArities = new HashSet<>();

		int[] nextFuncId = new int[1];

		int nextLocal = 1;

		int maxLocals = 1;

		int maxStack = 64;

		Ctx(ConstantPool cp, FieldrefConstant systemOut, MethodrefConstant printlnStr, MethodrefConstant lispToString,
				ClassConstant longClass, MethodrefConstant longValueOf, MethodrefConstant longValue,
				ClassConstant objectClass, ClassConstant objectArrayClass, ClassConstant integerClass,
				MethodrefConstant integerValueOf, MethodrefConstant integerValue) {
			this.cp = cp;
			this.systemOut = systemOut;
			this.printlnStr = printlnStr;
			this.lispToString = lispToString;
			this.longClass = longClass;
			this.longValueOf = longValueOf;
			this.longValue = longValue;
			this.objectClass = objectClass;
			this.objectArrayClass = objectArrayClass;
			this.integerClass = integerClass;
			this.integerValueOf = integerValueOf;
			this.integerValue = integerValue;
		}

		void emit(int opcode) {
			this.code.add(opcode);
		}

		void emitU2(int value) {
			byte[] bytes = ByteBuffer.allocate(2).putShort((short) value).array();
			this.code.add((int) bytes[0]);
			this.code.add((int) bytes[1]);
		}

		int allocLocal(String name) {
			int slot = this.nextLocal++;
			this.locals.put(name, slot);
			if (this.nextLocal > this.maxLocals) {
				this.maxLocals = this.nextLocal;
			}
			return slot;
		}

		int allocTemp() {
			int slot = this.nextLocal++;
			if (this.nextLocal > this.maxLocals) {
				this.maxLocals = this.nextLocal;
			}
			return slot;
		}

	}

}
