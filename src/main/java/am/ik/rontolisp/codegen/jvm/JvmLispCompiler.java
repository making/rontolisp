package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
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
import org.jspecify.annotations.Nullable;

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

		ClassConstant doubleClass = cp.addClass(cp.addUtf8("java/lang/Double"));
		MethodrefConstant doubleValueOf = cp.addMethodref(doubleClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(D)Ljava/lang/Double;")));
		MethodrefConstant doubleToString = cp.addMethodref(doubleClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));

		ClassConstant numberClass = cp.addClass(cp.addUtf8("java/lang/Number"));
		MethodrefConstant numberDoubleValue = cp.addMethodref(numberClass,
				cp.addNameAndType(cp.addUtf8("doubleValue"), cp.addUtf8("()D")));

		Utf8Constant lispToStringName = cp.addUtf8("_lispToString");
		Utf8Constant lispToStringDescUtf = cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/String;");
		MethodrefConstant lispToStringMethod = cp.addMethodref(thisClass,
				cp.addNameAndType(lispToStringName, lispToStringDescUtf));
		Utf8Constant consToStringName = cp.addUtf8("_consToString");
		Utf8Constant consToStringDescUtf = cp.addUtf8("([Ljava/lang/Object;)Ljava/lang/String;");
		MethodrefConstant consToStringMethod = cp.addMethodref(thisClass,
				cp.addNameAndType(consToStringName, consToStringDescUtf));
		ClassConstant stringClass = cp.addClass(cp.addUtf8("java/lang/String"));
		MethodrefConstant stringCharAt = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("charAt"), cp.addUtf8("(I)C")));
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

		// Reusable builder template with shared constants and state
		Ctx.Builder ctxBuilder = Ctx.builder()
			.cp(cp)
			.systemOut(systemOut)
			.printlnStr(printlnStr)
			.lispToString(lispToStringMethod)
			.longClass(longClass)
			.longValueOf(longValueOf)
			.longValue(longValue)
			.objectClass(objectClass)
			.objectArrayClass(objectArrayClass)
			.integerClass(integerClass)
			.integerValueOf(integerValueOf)
			.integerValue(integerValue)
			.doubleClass(doubleClass)
			.doubleValueOf(doubleValueOf)
			.numberClass(numberClass)
			.numberDoubleValue(numberDoubleValue)
			.stringClass(stringClass)
			.stringCharAt(stringCharAt)
			.functions(functions)
			.lambdaDecls(lambdaDecls)
			.indirectCallArities(indirectCallArities)
			.nextFuncId(nextFuncId);

		// Pass 2a: Compile each defun body
		List<Ctx> funcCtxs = new ArrayList<>();
		for (DefunDecl defun : defuns) {
			Ctx funcCtx = ctxBuilder.build();
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
						JvmEmitHelper.emitBoxLocal(funcCtx, slot);
					}
				}
			}
			for (int i = 0; i < defun.bodyExprs.size(); i++) {
				if (i > 0) {
					funcCtx.emit(Opcode.POP);
				}
				JvmExprCompiler.compileExpr(defun.bodyExprs.get(i), funcCtx, this.className);
			}
			funcCtx.emit(Opcode.ARETURN);
			funcCtxs.add(funcCtx);
		}

		// Pass 2b: Compile top-level expressions as main() body
		Ctx mainCtx = ctxBuilder.build();
		for (LispVal expr : topLevelExprs) {
			JvmExprCompiler.compileExpr(expr, mainCtx, this.className);
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

			Ctx lambdaCtx = ctxBuilder.build();
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
						JvmEmitHelper.emitBoxLocal(lambdaCtx, slot);
					}
				}
			}
			for (int i = 0; i < lambda.bodyExprs.size(); i++) {
				if (i > 0) {
					lambdaCtx.emit(Opcode.POP);
				}
				JvmExprCompiler.compileExpr(lambda.bodyExprs.get(i), lambdaCtx, this.className);
			}
			lambdaCtx.emit(Opcode.ARETURN);
			lambdaCtxs.add(lambdaCtx);
			lambdaIdx++;
		}

		// Build dispatch functions for each needed arity
		List<DispatchMethod> dispatchMethods = new ArrayList<>();
		for (int arity : indirectCallArities) {
			DispatchMethod dm = JvmRuntimeBuilder.buildDispatchMethod(arity, functions, lambdaDecls, lambdaFuncInfos,
					cp, thisClass, objectArrayClass, integerClass, integerValue);
			dispatchMethods.add(dm);
		}

		// Build _lispToString and _consToString helper method bodies
		List<Integer> ltsCode = JvmRuntimeBuilder.buildLispToStringBody(longClass, doubleClass, stringClass,
				objectArrayClass, integerClass, longToString, doubleToString, objectToString, consToStringMethod,
				nilStr, funcStr);
		List<Integer> ctsCode = JvmRuntimeBuilder.buildConsToStringBody(objectArrayClass, stringBuilderClass, sbInitStr,
				sbAppendStr, sbToString, lispToStringMethod, openParenStr, closeParenStr, spaceStr, dotStr);

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

	static boolean hasDoubleLiteral(List<LispVal> args) {
		for (int i = 1; i < args.size(); i++) {
			if (containsDouble(args.get(i))) {
				return true;
			}
		}
		return false;
	}

	static boolean containsDouble(LispVal val) {
		if (val instanceof LispDouble) {
			return true;
		}
		if (val instanceof LispCons cons) {
			for (LispVal element : cons.toList()) {
				if (containsDouble(element)) {
					return true;
				}
			}
		}
		return false;
	}

	static List<String> extractParamNames(LispVal paramsVal) {
		if (paramsVal instanceof LispNil) {
			return List.of();
		}
		return ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
	}

	static boolean isSetqLambda(LispVal expr) {
		if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym && "setq".equals(sym.name())) {
			List<LispVal> parts = cons.toList();
			if (parts.size() == 3 && parts.get(1) instanceof LispSymbol && parts.get(2) instanceof LispCons valueCons
					&& valueCons.car() instanceof LispSymbol lambdaSym && "lambda".equals(lambdaSym.name())) {
				return true;
			}
		}
		return false;
	}

	static DefunDecl extractSetqLambda(LispVal expr) {
		List<LispVal> parts = ((LispCons) expr).toList();
		String funcName = ((LispSymbol) parts.get(1)).name();
		List<LispVal> lambdaParts = ((LispCons) parts.get(2)).toList();
		List<String> paramNames = extractParamNames(lambdaParts.get(1));
		return new DefunDecl(funcName, paramNames, lambdaParts.subList(2, lambdaParts.size()));
	}

	record DefunDecl(String name, List<String> paramNames, List<LispVal> bodyExprs) {
	}

	record FunctionInfo(int funcId, int paramCount, boolean isClosure, MethodrefConstant methodref,
			Utf8Constant nameUtf8, Utf8Constant descUtf8) {
	}

	record LambdaInfo(int funcId, String methodName, List<String> paramNames, List<LispVal> bodyExprs,
			List<String> freeVarNames) {
	}

	record DispatchMethod(Utf8Constant nameUtf8, Utf8Constant descUtf8, List<Integer> code, int maxLocals) {
	}

	static final class Ctx {

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

		final ClassConstant doubleClass;

		final MethodrefConstant doubleValueOf;

		final ClassConstant numberClass;

		final MethodrefConstant numberDoubleValue;

		final ClassConstant stringClass;

		final MethodrefConstant stringCharAt;

		final List<Integer> code = new ArrayList<>();

		Map<String, Integer> locals = new HashMap<>();

		Map<String, FunctionInfo> functions;

		Map<String, Integer> captures = Map.of();

		Set<String> boxedVars = Set.of();

		int closureEnvSlot = -1;

		List<LambdaInfo> lambdaDecls;

		Set<Integer> indirectCallArities;

		int[] nextFuncId;

		int nextLocal = 1;

		int maxLocals = 1;

		int maxStack = 64;

		private Ctx(Builder builder) {
			this.cp = Objects.requireNonNull(builder.cp);
			this.systemOut = Objects.requireNonNull(builder.systemOut);
			this.printlnStr = Objects.requireNonNull(builder.printlnStr);
			this.lispToString = Objects.requireNonNull(builder.lispToString);
			this.longClass = Objects.requireNonNull(builder.longClass);
			this.longValueOf = Objects.requireNonNull(builder.longValueOf);
			this.longValue = Objects.requireNonNull(builder.longValue);
			this.objectClass = Objects.requireNonNull(builder.objectClass);
			this.objectArrayClass = Objects.requireNonNull(builder.objectArrayClass);
			this.integerClass = Objects.requireNonNull(builder.integerClass);
			this.integerValueOf = Objects.requireNonNull(builder.integerValueOf);
			this.integerValue = Objects.requireNonNull(builder.integerValue);
			this.doubleClass = Objects.requireNonNull(builder.doubleClass);
			this.doubleValueOf = Objects.requireNonNull(builder.doubleValueOf);
			this.numberClass = Objects.requireNonNull(builder.numberClass);
			this.numberDoubleValue = Objects.requireNonNull(builder.numberDoubleValue);
			this.stringClass = Objects.requireNonNull(builder.stringClass);
			this.stringCharAt = Objects.requireNonNull(builder.stringCharAt);
			this.functions = builder.functions;
			this.lambdaDecls = builder.lambdaDecls;
			this.indirectCallArities = builder.indirectCallArities;
			this.nextFuncId = builder.nextFuncId;
		}

		static Builder builder() {
			return new Builder();
		}

		static final class Builder {

			private @Nullable ConstantPool cp;

			private @Nullable FieldrefConstant systemOut;

			private @Nullable MethodrefConstant printlnStr;

			private @Nullable MethodrefConstant lispToString;

			private @Nullable ClassConstant longClass;

			private @Nullable MethodrefConstant longValueOf;

			private @Nullable MethodrefConstant longValue;

			private @Nullable ClassConstant objectClass;

			private @Nullable ClassConstant objectArrayClass;

			private @Nullable ClassConstant integerClass;

			private @Nullable MethodrefConstant integerValueOf;

			private @Nullable MethodrefConstant integerValue;

			private @Nullable ClassConstant doubleClass;

			private @Nullable MethodrefConstant doubleValueOf;

			private @Nullable ClassConstant numberClass;

			private @Nullable MethodrefConstant numberDoubleValue;

			private @Nullable ClassConstant stringClass;

			private @Nullable MethodrefConstant stringCharAt;

			private Map<String, FunctionInfo> functions = Map.of();

			private List<LambdaInfo> lambdaDecls = new ArrayList<>();

			private Set<Integer> indirectCallArities = new HashSet<>();

			private int[] nextFuncId = new int[1];

			Builder cp(ConstantPool cp) {
				this.cp = cp;
				return this;
			}

			Builder systemOut(FieldrefConstant systemOut) {
				this.systemOut = systemOut;
				return this;
			}

			Builder printlnStr(MethodrefConstant printlnStr) {
				this.printlnStr = printlnStr;
				return this;
			}

			Builder lispToString(MethodrefConstant lispToString) {
				this.lispToString = lispToString;
				return this;
			}

			Builder longClass(ClassConstant longClass) {
				this.longClass = longClass;
				return this;
			}

			Builder longValueOf(MethodrefConstant longValueOf) {
				this.longValueOf = longValueOf;
				return this;
			}

			Builder longValue(MethodrefConstant longValue) {
				this.longValue = longValue;
				return this;
			}

			Builder objectClass(ClassConstant objectClass) {
				this.objectClass = objectClass;
				return this;
			}

			Builder objectArrayClass(ClassConstant objectArrayClass) {
				this.objectArrayClass = objectArrayClass;
				return this;
			}

			Builder integerClass(ClassConstant integerClass) {
				this.integerClass = integerClass;
				return this;
			}

			Builder integerValueOf(MethodrefConstant integerValueOf) {
				this.integerValueOf = integerValueOf;
				return this;
			}

			Builder integerValue(MethodrefConstant integerValue) {
				this.integerValue = integerValue;
				return this;
			}

			Builder doubleClass(ClassConstant doubleClass) {
				this.doubleClass = doubleClass;
				return this;
			}

			Builder doubleValueOf(MethodrefConstant doubleValueOf) {
				this.doubleValueOf = doubleValueOf;
				return this;
			}

			Builder numberClass(ClassConstant numberClass) {
				this.numberClass = numberClass;
				return this;
			}

			Builder numberDoubleValue(MethodrefConstant numberDoubleValue) {
				this.numberDoubleValue = numberDoubleValue;
				return this;
			}

			Builder stringClass(ClassConstant stringClass) {
				this.stringClass = stringClass;
				return this;
			}

			Builder stringCharAt(MethodrefConstant stringCharAt) {
				this.stringCharAt = stringCharAt;
				return this;
			}

			Builder functions(Map<String, FunctionInfo> functions) {
				this.functions = functions;
				return this;
			}

			Builder lambdaDecls(List<LambdaInfo> lambdaDecls) {
				this.lambdaDecls = lambdaDecls;
				return this;
			}

			Builder indirectCallArities(Set<Integer> indirectCallArities) {
				this.indirectCallArities = indirectCallArities;
				return this;
			}

			Builder nextFuncId(int[] nextFuncId) {
				this.nextFuncId = nextFuncId;
				return this;
			}

			Ctx build() {
				return new Ctx(this);
			}

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
