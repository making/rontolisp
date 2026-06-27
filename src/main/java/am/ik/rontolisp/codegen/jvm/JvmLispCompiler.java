package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.compiler.BuiltinFunctionWrappers;
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

	private final boolean dynamic;

	/**
	 * Create a new JVM compiler targeting the given class name.
	 * @param className the fully qualified class name for the generated class
	 */
	public JvmLispCompiler(String className) {
		this(className, false);
	}

	/**
	 * Create a new JVM compiler targeting the given class name.
	 * @param className the fully qualified class name for the generated class
	 * @param dynamic when {@code true}, unresolved function calls and variable references
	 * are not rejected at compile time but resolved at runtime against the embedded
	 * {@code eval} global environment (late binding), so a program that defines functions
	 * via {@code load} can compile without changes. This forces the {@code eval} runtime
	 * to be emitted.
	 */
	public JvmLispCompiler(String className, boolean dynamic) {
		this.className = className;
		this.dynamic = dynamic;
	}

	@Override
	public byte[] compile(List<LispVal> program) {
		// Resolve packages (in-package directives, qualified symbols, *package*) up front
		// so
		// the rest of compilation sees canonical names.
		program = new PackageResolver().resolveProgram(program);
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
		MethodrefConstant printStr = cp.addMethodref(printStreamClass,
				cp.addNameAndType(cp.addUtf8("print"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant printlnVoid = cp.addMethodref(printStreamClass,
				cp.addNameAndType(cp.addUtf8("println"), cp.addUtf8("()V")));

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
		Utf8Constant lispToDisplayStringName = cp.addUtf8("_lispToDisplayString");
		MethodrefConstant lispToDisplayStringMethod = cp.addMethodref(thisClass,
				cp.addNameAndType(lispToDisplayStringName, lispToStringDescUtf));
		Utf8Constant consToDisplayStringName = cp.addUtf8("_consToDisplayString");
		MethodrefConstant consToDisplayStringMethod = cp.addMethodref(thisClass,
				cp.addNameAndType(consToDisplayStringName, consToStringDescUtf));
		Utf8Constant appendName = cp.addUtf8("_append");
		Utf8Constant appendDescUtf = cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		MethodrefConstant appendMethod = cp.addMethodref(thisClass, cp.addNameAndType(appendName, appendDescUtf));
		ClassConstant stringClass = cp.addClass(cp.addUtf8("java/lang/String"));
		MethodrefConstant stringCharAt = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("charAt"), cp.addUtf8("(I)C")));
		MethodrefConstant stringLength = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("length"), cp.addUtf8("()I")));
		MethodrefConstant stringSubstring = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("substring"), cp.addUtf8("(II)Ljava/lang/String;")));
		MethodrefConstant objectEquals = cp.addMethodref(objectClass,
				cp.addNameAndType(cp.addUtf8("equals"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		// Character runtime representation references (used by _lispToString /
		// _lispToDisplayString to print the #\name form and the bare glyph,
		// respectively).
		ClassConstant characterClass = cp.addClass(cp.addUtf8("java/lang/Character"));
		MethodrefConstant charValue = cp.addMethodref(characterClass,
				cp.addNameAndType(cp.addUtf8("charValue"), cp.addUtf8("()C")));
		MethodrefConstant stringValueOfChar = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(C)Ljava/lang/String;")));
		Utf8Constant charPrin1Name = cp.addUtf8("_charPrin1");
		Utf8Constant charPrin1Desc = cp.addUtf8("(C)Ljava/lang/String;");
		MethodrefConstant charPrin1Method = cp.addMethodref(thisClass, cp.addNameAndType(charPrin1Name, charPrin1Desc));
		ClassConstant mathClass = cp.addClass(cp.addUtf8("java/lang/Math"));
		MethodrefConstant mathAbsLong = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("abs"), cp.addUtf8("(J)J")));
		MethodrefConstant mathAbsDouble = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("abs"), cp.addUtf8("(D)D")));
		MethodrefConstant mathMinLong = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("min"), cp.addUtf8("(JJ)J")));
		MethodrefConstant mathMinDouble = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("min"), cp.addUtf8("(DD)D")));
		MethodrefConstant mathMaxLong = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("max"), cp.addUtf8("(JJ)J")));
		MethodrefConstant mathMaxDouble = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("max"), cp.addUtf8("(DD)D")));
		MethodrefConstant mathFloor = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("floor"), cp.addUtf8("(D)D")));
		MethodrefConstant mathCeil = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("ceil"), cp.addUtf8("(D)D")));
		MethodrefConstant mathRint = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("rint"), cp.addUtf8("(D)D")));

		// Math helper references for sqrt/exp/log/trig/expt/signum compilers.
		Map<String, MethodrefConstant> mathOps = JvmMathFnCompiler.buildOps(cp, mathClass);

		// System helper references for the time / getenv compilers.
		Map<String, MethodrefConstant> systemOps = new java.util.LinkedHashMap<>();
		systemOps.put("currentTimeMillis",
				cp.addMethodref(systemClass, cp.addNameAndType(cp.addUtf8("currentTimeMillis"), cp.addUtf8("()J"))));
		systemOps.put("nanoTime",
				cp.addMethodref(systemClass, cp.addNameAndType(cp.addUtf8("nanoTime"), cp.addUtf8("()J"))));
		systemOps.put("getenv", cp.addMethodref(systemClass,
				cp.addNameAndType(cp.addUtf8("getenv"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;"))));

		// read-line helper
		ClassConstant bufferedReaderClass = cp.addClass(cp.addUtf8("java/io/BufferedReader"));
		ClassConstant inputStreamReaderClass = cp.addClass(cp.addUtf8("java/io/InputStreamReader"));
		MethodrefConstant brInit = cp.addMethodref(bufferedReaderClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/io/Reader;)V")));
		MethodrefConstant brReadLine = cp.addMethodref(bufferedReaderClass,
				cp.addNameAndType(cp.addUtf8("readLine"), cp.addUtf8("()Ljava/lang/String;")));
		MethodrefConstant isrInit = cp.addMethodref(inputStreamReaderClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/io/InputStream;)V")));
		FieldrefConstant systemIn = cp.addFieldref(systemClass,
				cp.addNameAndType(cp.addUtf8("in"), cp.addUtf8("Ljava/io/InputStream;")));
		MethodrefConstant stringConcat = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("concat"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;")));
		Utf8Constant stdinReaderFieldName = cp.addUtf8("_stdinReader");
		Utf8Constant stdinReaderFieldDesc = cp.addUtf8("Ljava/io/BufferedReader;");
		FieldrefConstant stdinReaderField = cp.addFieldref(thisClass,
				cp.addNameAndType(stdinReaderFieldName, stdinReaderFieldDesc));
		Utf8Constant readLineHelperName = cp.addUtf8("_readLine");
		Utf8Constant readLineHelperDesc = cp.addUtf8("()Ljava/lang/Object;");
		MethodrefConstant readLineHelperMethod = cp.addMethodref(thisClass,
				cp.addNameAndType(readLineHelperName, readLineHelperDesc));

		// fetch helper: emitted only when the program uses rontolisp:fetch.
		String fetchQualified = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.FETCH);
		boolean usesFetch = programUsesSymbol(program, fetchQualified);
		MethodrefConstant fetchHelperMethod = usesFetch
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmFetchRuntimeBuilder.METHOD_NAME),
						cp.addUtf8(JvmFetchRuntimeBuilder.METHOD_DESC)))
				: null;

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
		ClassConstant ratioArrayClass = cp.addClass(cp.addUtf8("[Ljava/math/BigInteger;"));
		ConstantPool.StringConstant nilStr = cp.addString("nil");
		ConstantPool.StringConstant funcStr = cp.addString("#<function>");
		ConstantPool.StringConstant slashStr = cp.addString("/");
		ConstantPool.StringConstant openParenStr = cp.addString("(");
		ConstantPool.StringConstant closeParenStr = cp.addString(")");
		ConstantPool.StringConstant spaceStr = cp.addString(" ");
		ConstantPool.StringConstant dotStr = cp.addString(" . ");

		// Pass 1: Collect defun declarations and top-level expressions. Lisp-2: only a
		// real (defun ...) form defines a function; a top-level (setq name (lambda ...))
		// binds a variable to a closure like any other setq.
		List<DefunDecl> defuns = new ArrayList<>();
		List<LispVal> topLevelExprs = new ArrayList<>();
		for (LispVal expr : program) {
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym
					&& LispNames.DEFUN.equals(sym.name())) {
				defuns.add(extractSetqLambda(LispMacroExpander.expandDefun(cons)));
			}
			else {
				topLevelExprs.add(expr);
			}
		}

		// Inject built-in function wrappers (user defuns take priority)
		Set<String> userDefinedNames = new HashSet<>();
		for (DefunDecl defun : defuns) {
			userDefinedNames.add(defun.name);
		}
		// parse-integer / read-from-string wrappers reference runtime helpers that are
		// emitted only when the program itself uses the operator (_parseInt; the reader
		// runtime). Exclude each wrapper unless the program references the symbol, so the
		// wrapper and its helper stay gated together.
		Set<String> wrapperExcludes = new HashSet<>();
		if (!programUsesSymbol(program, LispNames.PARSE_INTEGER)) {
			wrapperExcludes.add(LispNames.PARSE_INTEGER);
		}
		if (!(programUsesSymbol(program, LispNames.READ) || programUsesSymbol(program, LispNames.READ_FROM_STRING)
				|| programUsesSymbol(program, LispNames.LOAD))) {
			wrapperExcludes.add(LispNames.READ_FROM_STRING);
		}
		// Hash-table wrappers reference helpers (JvmHashRuntimeBuilder) emitted only when
		// the program uses a hash table; gate the whole group together.
		if (!programUsesAnyHashOp(program)) {
			wrapperExcludes.addAll(BuiltinFunctionWrappers.HASH_FUNCTIONS);
		}
		for (LispVal wrapper : BuiltinFunctionWrappers.generate(userDefinedNames, wrapperExcludes)) {
			defuns.add(extractSetqLambda(wrapper));
		}

		// Assign funcIds and register in CP
		int[] nextFuncId = { 0 };
		Map<String, FunctionInfo> functions = new HashMap<>();
		for (DefunDecl defun : defuns) {
			int funcId = nextFuncId[0]++;
			String descriptor = "(" + "Ljava/lang/Object;".repeat(defun.paramNames.size()) + ")Ljava/lang/Object;";
			Utf8Constant nameUtf8 = cp.addUtf8(mangleMethodName(defun.name));
			Utf8Constant descUtf8 = cp.addUtf8(descriptor);
			MethodrefConstant methodref = cp.addMethodref(thisClass, cp.addNameAndType(nameUtf8, descUtf8));
			functions.put(defun.name,
					new FunctionInfo(funcId, defun.paramNames.size(), false, methodref, nameUtf8, descUtf8));
		}

		// Shared state for lambda discovery
		List<LambdaInfo> lambdaDecls = new ArrayList<>();
		Set<Integer> indirectCallArities = new HashSet<>();

		// The reader runtime is emitted for read/load; load also evaluates each form, so
		// it pulls in the eval runtime as well.
		boolean usesLoad = programUsesSymbol(program, LispNames.LOAD);
		boolean usesRead = programUsesSymbol(program, LispNames.READ)
				|| programUsesSymbol(program, LispNames.READ_FROM_STRING) || usesLoad;

		// When the program uses eval, the runtime _apply dispatches by argument count, so
		// every arity up to the maximum callable must have a dispatch method. The apply
		// built-in reuses _apply, so it forces the eval runtime to be emitted as well.
		boolean usesEval = programUsesEval(program) || usesLoad || this.dynamic
				|| programUsesSymbol(program, LispNames.APPLY);
		if (usesEval) {
			for (int arity = 0; arity <= JvmEvalRuntimeBuilder.MAX_CALLABLE_ARITY; arity++) {
				indirectCallArities.add(arity);
			}
		}

		// Numeric runtime helpers (long arithmetic with automatic BigInteger promotion)
		JvmNumericRuntimeBuilder.NumericRuntime numericRuntime = JvmNumericRuntimeBuilder.build(cp, thisClass);

		// Reusable builder template with shared constants and state
		Ctx.Builder ctxBuilder = Ctx.builder()
			.cp(cp)
			.numOps(numericRuntime.ops())
			.mathOps(mathOps)
			.systemOps(systemOps)
			.systemOut(systemOut)
			.printlnStr(printlnStr)
			.lispToString(lispToStringMethod)
			.printStr(printStr)
			.printlnVoid(printlnVoid)
			.lispToDisplayString(lispToDisplayStringMethod)
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
			.nextFuncId(nextFuncId)
			.appendMethod(appendMethod)
			.mathAbsLong(mathAbsLong)
			.mathAbsDouble(mathAbsDouble)
			.mathMinLong(mathMinLong)
			.mathMinDouble(mathMinDouble)
			.mathMaxLong(mathMaxLong)
			.mathMaxDouble(mathMaxDouble)
			.mathFloor(mathFloor)
			.mathCeil(mathCeil)
			.mathRint(mathRint)
			.objectEquals(objectEquals)
			.readLineHelper(readLineHelperMethod)
			.fetchHelper(fetchHelperMethod)
			.dynamic(this.dynamic)
			.className(this.className)
			.userDefunNames(Set.copyOf(userDefinedNames));

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
		mainCtx.topLevel = true;
		// When eval is present, top-level global variable bindings (setq/defvar/...) are
		// mirrored into the eval runtime's global environment via _store, so an eval'd
		// expression can resolve them (the compiled value lives in a main() local that
		// the
		// runtime interpreter cannot reach).
		if (usesEval) {
			mainCtx.evalStoreRef = cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8("_store"),
					cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")));
		}
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

		// Build dispatch functions for each needed arity. When the eval runtime is
		// present, the dispatcher falls back to _apply for interpreted closures
		// (funcId == -1) created by the runtime's lambda.
		MethodrefConstant applyRefForDispatch = usesEval
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8("_apply"),
						cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")))
				: null;
		List<DispatchMethod> dispatchMethods = new ArrayList<>();
		for (int arity : indirectCallArities) {
			DispatchMethod dm = JvmRuntimeBuilder.buildDispatchMethod(arity, functions, lambdaDecls, lambdaFuncInfos,
					cp, thisClass, objectArrayClass, integerClass, integerValue, objectClass, applyRefForDispatch);
			dispatchMethods.add(dm);
		}

		// Build the eval runtime methods and the global-environment field (only when
		// used)
		Utf8Constant evalName = cp.addUtf8("_eval");
		Utf8Constant evalDesc = cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		Utf8Constant applyName = cp.addUtf8("_apply");
		Utf8Constant storeName = cp.addUtf8("_store");
		Utf8Constant storeDesc = cp
			.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		Utf8Constant envLookupName = cp.addUtf8("_envLookup");
		Utf8Constant envLookupDesc = cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		Utf8Constant lookupName = cp.addUtf8("_lookup");
		Utf8Constant lookupDesc = cp.addUtf8("(Ljava/lang/Object;)[Ljava/lang/Object;");
		Utf8Constant genvName = cp.addUtf8("_genv");
		Utf8Constant genvDesc = cp.addUtf8("Ljava/lang/Object;");
		FieldrefConstant genvField = cp.addFieldref(thisClass, cp.addNameAndType(genvName, genvDesc));
		Utf8Constant fenvName = cp.addUtf8("_fenv");
		FieldrefConstant fenvField = cp.addFieldref(thisClass, cp.addNameAndType(fenvName, genvDesc));
		List<Integer> evalCode = List.of();
		List<Integer> applyCode = List.of();
		List<Integer> storeCode = List.of();
		List<Integer> envLookupCode = List.of();
		List<Integer> lookupCode = List.of();
		if (usesEval) {
			MethodrefConstant evalRef = cp.addMethodref(thisClass, cp.addNameAndType(evalName, evalDesc));
			MethodrefConstant applyRef = cp.addMethodref(thisClass, cp.addNameAndType(applyName, evalDesc));
			MethodrefConstant storeRef = cp.addMethodref(thisClass, cp.addNameAndType(storeName, storeDesc));
			MethodrefConstant envLookupRef = cp.addMethodref(thisClass,
					cp.addNameAndType(envLookupName, envLookupDesc));
			MethodrefConstant lookupRef = cp.addMethodref(thisClass, cp.addNameAndType(lookupName, lookupDesc));
			MethodrefConstant[] invoke = new MethodrefConstant[JvmEvalRuntimeBuilder.MAX_CALLABLE_ARITY + 1];
			for (int n = 0; n <= JvmEvalRuntimeBuilder.MAX_CALLABLE_ARITY; n++) {
				Utf8Constant invName = cp.addUtf8("_invoke_" + n);
				Utf8Constant invDesc = cp.addUtf8("(" + "Ljava/lang/Object;".repeat(n + 1) + ")Ljava/lang/Object;");
				invoke[n] = cp.addMethodref(thisClass, cp.addNameAndType(invName, invDesc));
			}
			MethodrefConstant stringLengthRef = cp.addMethodref(stringClass,
					cp.addNameAndType(cp.addUtf8("length"), cp.addUtf8("()I")));
			JvmEvalRuntimeBuilder.EvalConstants ec = JvmEvalRuntimeBuilder.EvalConstants.builder()
				.cp(cp)
				.objectClass(objectClass)
				.objectArrayClass(objectArrayClass)
				.integerClass(integerClass)
				.longClass(longClass)
				.doubleClass(doubleClass)
				.stringClass(stringClass)
				.integerValueOf(integerValueOf)
				.integerValue(integerValue)
				.longValueOf(longValueOf)
				.longValue(longValue)
				.stringCharAt(stringCharAt)
				.stringLength(stringLengthRef)
				.objectEquals(objectEquals)
				.evalRef(evalRef)
				.applyRef(applyRef)
				.storeRef(storeRef)
				.envLookupRef(envLookupRef)
				.lookupRef(lookupRef)
				.genvField(genvField)
				.fenvField(fenvField)
				.invoke(invoke)
				.functions(functions)
				.build();
			evalCode = JvmEvalRuntimeBuilder.buildEval(ec);
			applyCode = JvmEvalRuntimeBuilder.buildApply(ec);
			storeCode = JvmEvalRuntimeBuilder.buildStore(ec);
			envLookupCode = JvmEvalRuntimeBuilder.buildEnvLookup(ec);
			lookupCode = JvmEvalRuntimeBuilder.buildLookup(ec);
		}

		// Build the runtime reader methods (read/load), only when used
		Utf8Constant readSrcName = cp.addUtf8("_readSrc");
		Utf8Constant readSrcDesc = cp.addUtf8("Ljava/lang/String;");
		Utf8Constant readPosName = cp.addUtf8("_readPos");
		Utf8Constant readPosDesc = cp.addUtf8("I");
		List<JvmReadRuntimeBuilder.ReadMethod> readMethods = List.of();
		if (usesRead) {
			readMethods = JvmReadRuntimeBuilder
				.create(cp, thisClass, objectClass, objectArrayClass, stringClass, longValueOf, doubleValueOf,
						stringCharAt, stringLength, stringSubstring, objectEquals, readLineHelperMethod, usesLoad)
				.methods();
		}
		final List<JvmReadRuntimeBuilder.ReadMethod> readMethodsFinal = readMethods;

		// Build the hash-table runtime helpers, only when the program uses hash tables.
		boolean usesHashTables = programUsesAnyHashOp(program);
		final List<JvmHashRuntimeBuilder.HashMethod> hashMethods = usesHashTables ? JvmHashRuntimeBuilder.build(cp,
				thisClass, objectClass, objectArrayClass, longValueOf, lispToStringMethod) : List.of();

		// Build the array runtime helpers, only when the program uses arrays. Includes
		// the
		// two array-printing helpers (_arrayToString / _arrayToDisplayString) so a
		// literal
		// or make-array result prints as #(...) / #2A(...).
		boolean usesArrays = programUsesAnyArrayOp(program);
		final List<JvmArrayRuntimeBuilder.ArrayMethod> arrayMethods;
		if (usesArrays) {
			List<JvmArrayRuntimeBuilder.ArrayMethod> built = new ArrayList<>(
					JvmArrayRuntimeBuilder.build(cp, objectClass, objectArrayClass));
			built
				.addAll(JvmArrayRuntimeBuilder.buildToStringMethods(cp, lispToStringMethod, lispToDisplayStringMethod));
			arrayMethods = built;
		}
		else {
			arrayMethods = List.of();
		}
		ClassConstant arrayListClassForPrint = usesArrays ? cp.addClass(cp.addUtf8("java/util/ArrayList")) : null;
		MethodrefConstant arrayToStringMethod = usesArrays
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmArrayRuntimeBuilder.TO_STRING),
						cp.addUtf8(JvmArrayRuntimeBuilder.TO_STRING_DESC)))
				: null;
		MethodrefConstant arrayToDisplayStringMethod = usesArrays
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmArrayRuntimeBuilder.TO_DISPLAY_STRING),
						cp.addUtf8(JvmArrayRuntimeBuilder.TO_STRING_DESC)))
				: null;

		// Build _lispToString and _consToString helper method bodies
		List<Integer> ltsCode = JvmRuntimeBuilder.buildLispToStringBody(longClass, doubleClass, stringClass,
				objectArrayClass, integerClass, longToString, doubleToString, objectToString, consToStringMethod,
				nilStr, funcStr, ratioArrayClass, stringConcat, slashStr, characterClass, charValue, charPrin1Method,
				arrayListClassForPrint, arrayToStringMethod);
		List<Integer> ctsCode = JvmRuntimeBuilder.buildConsToStringBody(objectArrayClass, stringBuilderClass, sbInitStr,
				sbAppendStr, sbToString, lispToStringMethod, openParenStr, closeParenStr, spaceStr, dotStr,
				ratioArrayClass);
		List<Integer> ltdsCode = JvmRuntimeBuilder.buildLispToDisplayStringBody(longClass, doubleClass, stringClass,
				objectArrayClass, integerClass, longToString, doubleToString, objectToString, consToDisplayStringMethod,
				nilStr, funcStr, stringCharAt, stringLength, stringSubstring, ratioArrayClass, stringConcat, slashStr,
				characterClass, charValue, stringValueOfChar, arrayListClassForPrint, arrayToDisplayStringMethod);
		List<Integer> charPrin1Code = JvmRuntimeBuilder.buildCharPrin1Body(cp, stringConcat, stringValueOfChar);
		List<Integer> ctdsCode = JvmRuntimeBuilder.buildConsToDisplayStringBody(objectArrayClass, stringBuilderClass,
				sbInitStr, sbAppendStr, sbToString, lispToDisplayStringMethod, openParenStr, closeParenStr, spaceStr,
				dotStr, ratioArrayClass);
		List<Integer> appendCode = JvmRuntimeBuilder.buildAppendBody(objectArrayClass, objectClass, appendMethod);
		ConstantPool.StringConstant quoteStr = cp.addString("\"");
		List<Integer> readLineCode = JvmRuntimeBuilder.buildReadLineBody(bufferedReaderClass, inputStreamReaderClass,
				brInit, brReadLine, isrInit, systemIn, stdinReaderField, quoteStr, stringConcat);

		// File-stream runtime (open/close/write-line/read-line with a stream)
		MethodrefConstant stringLengthForIo = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("length"), cp.addUtf8("()I")));
		List<JvmIoRuntimeBuilder.IoMethod> ioMethods = JvmIoRuntimeBuilder
			.create(cp, thisClass, objectClass, stringClass, longClass, longValueOf, longValue, stringLengthForIo,
					stringSubstring, stringConcat, systemOut, printlnStr, readLineHelperMethod)
			.methods();
		Utf8Constant streamsFieldName = cp.addUtf8(JvmIoRuntimeBuilder.STREAMS_FIELD);
		Utf8Constant streamsFieldDesc = cp.addUtf8(JvmIoRuntimeBuilder.STREAMS_DESC);
		Utf8Constant streamCountFieldName = cp.addUtf8(JvmIoRuntimeBuilder.STREAM_COUNT_FIELD);
		Utf8Constant streamCountFieldDesc = cp.addUtf8(JvmIoRuntimeBuilder.STREAM_COUNT_DESC);
		// Tracks whether stdout is at the start of a line (0 = at line start), so
		// fresh-line
		// can decide whether to emit a newline. A static int defaults to 0 (at line
		// start).
		Utf8Constant colFieldName = cp.addUtf8(JvmFreshLineCompiler.COL_FIELD);
		Utf8Constant colFieldDesc = cp.addUtf8(JvmFreshLineCompiler.COL_DESC);

		// http-get runtime helper body (only when the program uses rontolisp:http-get).
		final JvmFetchRuntimeBuilder.@Nullable FetchMethod fetchMethodBody = usesFetch
				? JvmFetchRuntimeBuilder.build(cp, thisClass, objectClass, objectArrayClass, stringClass, longValueOf,
						stringLength, stringSubstring, stringConcat)
				: null;

		// parse-integer runtime helper, emitted only when the program uses parse-integer.
		boolean usesParseInteger = programUsesSymbol(program, LispNames.PARSE_INTEGER);
		final JvmParseIntegerRuntimeBuilder.@Nullable ParseIntMethod parseIntMethodBody = usesParseInteger
				? JvmParseIntegerRuntimeBuilder.build(cp, stringClass, longClass, longValueOf) : null;

		// length runtime helper. Emitted unconditionally (it is small and lives in its
		// own
		// method): length is also generated internally by other compilers (e.g. format
		// padding), so a source-symbol gate would miss those call sites. The whole
		// computation lives in one method so each call site is a single invokestatic,
		// keeping main within the JVM's 64 KB per-method limit.
		final JvmLengthRuntimeBuilder.LengthMethod lengthMethodBody = JvmLengthRuntimeBuilder.build(cp,
				objectArrayClass, stringClass, longValueOf);

		Utf8Constant mainUtf8 = cp.addUtf8("main");
		Utf8Constant mainDesc = cp.addUtf8("([Ljava/lang/String;)V");
		Utf8Constant codeUtf8 = cp.addUtf8("Code");

		// Effectively-final aliases for capture in the writer lambda
		final List<Integer> evalBody = evalCode;
		final List<Integer> applyBody = applyCode;
		final List<Integer> storeBody = storeCode;
		final List<Integer> envLookupBody = envLookupCode;
		final List<Integer> lookupBody = lookupCode;

		ByteArrayOutputStream classOut = new ByteArrayOutputStream();
		new ByteCodeWriter(classOut) //
			.write(0xCA, 0xFE, 0xBA, 0xBE) //
			.writeVersion(0, 50) //
			.writeConstantPool(cp) //
			.writeClass(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_SUPER, thisClass, objectClass) //
			.writeInterfaces(i -> {
			})
			.writeFields(f -> {
				f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
					.writeU2(stdinReaderFieldName)
					.writeU2(stdinReaderFieldDesc)
					.writeU2(0));
				f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
					.writeU2(streamsFieldName)
					.writeU2(streamsFieldDesc)
					.writeU2(0));
				f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
					.writeU2(streamCountFieldName)
					.writeU2(streamCountFieldDesc)
					.writeU2(0));
				f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
					.writeU2(colFieldName)
					.writeU2(colFieldDesc)
					.writeU2(0));
				if (usesEval) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(genvName)
						.writeU2(genvDesc)
						.writeU2(0));
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(fenvName)
						.writeU2(genvDesc)
						.writeU2(0));
				}
				if (usesRead) {
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(readSrcName)
						.writeU2(readSrcDesc)
						.writeU2(0));
					f.add(w -> w.writeU2(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC)
						.writeU2(readPosName)
						.writeU2(readPosDesc)
						.writeU2(0));
				}
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
							attr.writeU2(3)
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
				methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, appendName, appendDescUtf,
						method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(5)
								.writeU2(3)
								.writeCode((Object[]) appendCode.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
				methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, readLineHelperName, readLineHelperDesc,
						method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(5)
								.writeU2(1)
								.writeCode((Object[]) readLineCode.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
				for (JvmIoRuntimeBuilder.IoMethod im : ioMethods) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, im.name(), im.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(im.maxStack())
									.writeU2(im.maxLocals())
									.writeCode((Object[]) im.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				if (fetchMethodBody != null) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, fetchMethodBody.name(),
							fetchMethodBody.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(fetchMethodBody.maxStack())
									.writeU2(fetchMethodBody.maxLocals())
									.writeCode((Object[]) fetchMethodBody.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				if (parseIntMethodBody != null) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, parseIntMethodBody.name(),
							parseIntMethodBody.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(parseIntMethodBody.maxStack())
									.writeU2(parseIntMethodBody.maxLocals())
									.writeCode((Object[]) parseIntMethodBody.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				{
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, lengthMethodBody.name(),
							lengthMethodBody.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(lengthMethodBody.maxStack())
									.writeU2(lengthMethodBody.maxLocals())
									.writeCode((Object[]) lengthMethodBody.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				for (JvmReadRuntimeBuilder.ReadMethod rm : readMethodsFinal) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, rm.name(), rm.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(rm.maxStack())
									.writeU2(rm.maxLocals())
									.writeCode((Object[]) rm.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				for (JvmHashRuntimeBuilder.HashMethod hm : hashMethods) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, hm.name(), hm.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(hm.maxStack())
									.writeU2(hm.maxLocals())
									.writeCode((Object[]) hm.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				for (JvmArrayRuntimeBuilder.ArrayMethod am : arrayMethods) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, am.name(), am.desc(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(am.maxStack())
									.writeU2(am.maxLocals())
									.writeCode((Object[]) am.code().toArray(new Integer[0]))
									.writeU2(0)
									.writeU2(0);
							})));
				}
				for (JvmNumericRuntimeBuilder.NumericMethod nm : numericRuntime.methods()) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, nm.nameUtf8(), nm.descUtf8(),
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(nm.maxStack())
									.writeU2(nm.maxLocals())
									.writeCode((Object[]) nm.code().toArray(new Integer[0]))
									.writeU2(nm.exceptionTable().size());
								for (int[] entry : nm.exceptionTable()) {
									attr.writeU2(entry[0]).writeU2(entry[1]).writeU2(entry[2]).writeU2(entry[3]);
								}
								attr.writeU2(0);
							})));
				}
				methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, lispToDisplayStringName,
						lispToStringDescUtf, method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(4)
								.writeU2(2)
								.writeCode((Object[]) ltdsCode.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
				methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, consToDisplayStringName,
						consToStringDescUtf, method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(3)
								.writeU2(5)
								.writeCode((Object[]) ctdsCode.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
				methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, charPrin1Name, charPrin1Desc,
						method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(3)
								.writeU2(1)
								.writeCode((Object[]) charPrin1Code.toArray(new Integer[0]))
								.writeU2(0)
								.writeU2(0);
						})));
				if (usesEval) {
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, lookupName, lookupDesc,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8,
									attr -> attr.writeU2(8)
										.writeU2(2)
										.writeCode((Object[]) lookupBody.toArray(new Integer[0]))
										.writeU2(0)
										.writeU2(0))));
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, envLookupName, envLookupDesc,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8,
									attr -> attr.writeU2(8)
										.writeU2(5)
										.writeCode((Object[]) envLookupBody.toArray(new Integer[0]))
										.writeU2(0)
										.writeU2(0))));
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, evalName, evalDesc,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8,
									attr -> attr.writeU2(32)
										.writeU2(22)
										.writeCode((Object[]) evalBody.toArray(new Integer[0]))
										.writeU2(0)
										.writeU2(0))));
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, applyName, evalDesc,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8,
									attr -> attr.writeU2(32)
										.writeU2(20)
										.writeCode((Object[]) applyBody.toArray(new Integer[0]))
										.writeU2(0)
										.writeU2(0))));
					methods.add(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, storeName, storeDesc,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8,
									attr -> attr.writeU2(32)
										.writeU2(14)
										.writeCode((Object[]) storeBody.toArray(new Integer[0]))
										.writeU2(0)
										.writeU2(0))));
				}
			}) //
			.writeAttributes(a -> {
			});
		return classOut.toByteArray();
	}

	private static boolean programUsesEval(List<LispVal> program) {
		for (LispVal expr : program) {
			if (usesEval(expr)) {
				return true;
			}
		}
		return false;
	}

	private static boolean programUsesSymbol(List<LispVal> program, String name) {
		for (LispVal expr : program) {
			if (usesSymbol(expr, name)) {
				return true;
			}
		}
		return false;
	}

	// True when the program references any hash-table operator (including (setf (gethash
	// ...)) which contains gethash). Gates both the runtime helpers and the first-class
	// wrappers so they stay emitted together.
	private static boolean programUsesAnyHashOp(List<LispVal> program) {
		return programUsesSymbol(program, LispNames.MAKE_HASH_TABLE) || programUsesSymbol(program, LispNames.GETHASH)
				|| programUsesSymbol(program, LispNames.REMHASH) || programUsesSymbol(program, LispNames.CLRHASH)
				|| programUsesSymbol(program, LispNames.HASH_TABLE_COUNT)
				|| programUsesSymbol(program, LispNames.HASH_TABLE_P) || programUsesSymbol(program, LispNames.MAPHASH);
	}

	private static boolean programUsesAnyArrayOp(List<LispVal> program) {
		return programUsesSymbol(program, LispNames.MAKE_ARRAY) || programUsesSymbol(program, LispNames.AREF)
				|| programUsesSymbol(program, LispNames.ASET) || programContainsArrayLiteral(program);
	}

	// True when a self-evaluating array literal (#(...)) appears anywhere in the program,
	// so the array runtime helpers (used to print it) are emitted even without an
	// explicit
	// make-array/aref call.
	private static boolean programContainsArrayLiteral(List<LispVal> program) {
		for (LispVal expr : program) {
			if (containsArrayLiteral(expr)) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsArrayLiteral(LispVal val) {
		if (val instanceof am.ik.rontolisp.LispArray) {
			return true;
		}
		if (val instanceof LispCons cons) {
			return containsArrayLiteral(cons.car()) || containsArrayLiteral(cons.cdr());
		}
		return false;
	}

	private static boolean usesSymbol(LispVal val, String name) {
		if (!(val instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym && name.equals(sym.name())) {
			return true;
		}
		return usesSymbol(cons.car(), name) || usesSymbol(cons.cdr(), name);
	}

	private static boolean usesEval(LispVal val) {
		if (!(val instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym && LispNames.EVAL.equals(sym.name())) {
			return true;
		}
		return usesEval(cons.car()) || usesEval(cons.cdr());
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

	static DefunDecl extractSetqLambda(LispVal expr) {
		List<LispVal> parts = ((LispCons) expr).toList();
		String funcName = ((LispSymbol) parts.get(1)).name();
		List<LispVal> lambdaParts = ((LispCons) parts.get(2)).toList();
		List<String> paramNames = extractParamNames(lambdaParts.get(1));
		return new DefunDecl(funcName, paramNames, lambdaParts.subList(2, lambdaParts.size()));
	}

	/**
	 * Mangles a Lisp function name into a valid JVM method name. The JVM spec forbids
	 * {@code /}, {@code <}, {@code >}, {@code .}, {@code ;}, {@code [} in unqualified
	 * names.
	 */
	static String mangleMethodName(String name) {
		String mangled = switch (name) {
			case "/" -> "$div";
			case "<" -> "$lt";
			case ">" -> "$gt";
			case "<=" -> "$le";
			case ">=" -> "$ge";
			default -> name;
		};
		// Package-qualified names (e.g. rontolisp:foo) cannot contain ':' in a JVM method
		// name; map it so user-defined symbols of non-default packages compile. The same
		// applies to any residual '<'/'>' the exact-match switch above did not consume
		// (e.g. the char</char<= wrapper names), which the JVM reserves for
		// <init>/<clinit>.
		if (mangled.indexOf(':') >= 0) {
			mangled = mangled.replace(":", "$colon");
		}
		if (mangled.indexOf('<') >= 0) {
			mangled = mangled.replace("<", "$lt");
		}
		if (mangled.indexOf('>') >= 0) {
			mangled = mangled.replace(">", "$gt");
		}
		return mangled;
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

	/**
	 * An active {@code %block} return boundary during compilation. {@code rvSlot} is the
	 * local that holds the block's value; {@code exitPatches} collects the positions of
	 * the {@code goto} instructions emitted by {@code return} forms, all back-patched to
	 * the block's exit once its body has been compiled.
	 */
	record BlockTarget(int rvSlot, List<Integer> exitPatches) {
	}

	static final class Ctx {

		final ConstantPool cp;

		final FieldrefConstant systemOut;

		final MethodrefConstant printlnStr;

		final MethodrefConstant lispToString;

		final MethodrefConstant printStr;

		final MethodrefConstant printlnVoid;

		final MethodrefConstant lispToDisplayString;

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

		final MethodrefConstant appendMethod;

		final MethodrefConstant mathAbsLong;

		final MethodrefConstant mathAbsDouble;

		final MethodrefConstant mathMinLong;

		final MethodrefConstant mathMinDouble;

		final MethodrefConstant mathMaxLong;

		final MethodrefConstant mathMaxDouble;

		final MethodrefConstant mathFloor;

		final MethodrefConstant mathCeil;

		final MethodrefConstant mathRint;

		final MethodrefConstant objectEquals;

		final MethodrefConstant readLineHelper;

		final @Nullable MethodrefConstant fetchHelper;

		Map<String, MethodrefConstant> numOps = Map.of();

		Map<String, MethodrefConstant> mathOps = Map.of();

		Map<String, MethodrefConstant> systemOps = Map.of();

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

		boolean dynamic = false;

		/**
		 * True for the single context that compiles top-level forms (the {@code main}
		 * body), false for defun/lambda bodies. When the embedded {@code eval} runtime is
		 * present, a top-level global variable binding is mirrored into the runtime's
		 * global environment so {@code eval} can resolve it (see {@link #evalStoreRef}).
		 */
		boolean topLevel = false;

		/**
		 * The {@code _store(place, value, env)} methodref, set only when the program uses
		 * {@code eval}. Used to mirror top-level global variable bindings into the eval
		 * runtime's global environment; null otherwise.
		 */
		@Nullable MethodrefConstant evalStoreRef;

		String className = "";

		Set<String> userDefunNames = Set.of();

		/**
		 * Stack of active {@code %block} return boundaries. The innermost block is on
		 * top; a {@code return} stores its value into the block's slot and jumps to its
		 * exit.
		 */
		final Deque<BlockTarget> blockTargets = new ArrayDeque<>();

		private Ctx(Builder builder) {
			this.dynamic = builder.dynamic;
			this.className = builder.className;
			this.userDefunNames = builder.userDefunNames;
			this.cp = Objects.requireNonNull(builder.cp);
			this.systemOut = Objects.requireNonNull(builder.systemOut);
			this.printlnStr = Objects.requireNonNull(builder.printlnStr);
			this.lispToString = Objects.requireNonNull(builder.lispToString);
			this.printStr = Objects.requireNonNull(builder.printStr);
			this.printlnVoid = Objects.requireNonNull(builder.printlnVoid);
			this.lispToDisplayString = Objects.requireNonNull(builder.lispToDisplayString);
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
			this.appendMethod = Objects.requireNonNull(builder.appendMethod);
			this.mathAbsLong = Objects.requireNonNull(builder.mathAbsLong);
			this.mathAbsDouble = Objects.requireNonNull(builder.mathAbsDouble);
			this.mathMinLong = Objects.requireNonNull(builder.mathMinLong);
			this.mathMinDouble = Objects.requireNonNull(builder.mathMinDouble);
			this.mathMaxLong = Objects.requireNonNull(builder.mathMaxLong);
			this.mathMaxDouble = Objects.requireNonNull(builder.mathMaxDouble);
			this.mathFloor = Objects.requireNonNull(builder.mathFloor);
			this.mathCeil = Objects.requireNonNull(builder.mathCeil);
			this.mathRint = Objects.requireNonNull(builder.mathRint);
			this.objectEquals = Objects.requireNonNull(builder.objectEquals);
			this.readLineHelper = Objects.requireNonNull(builder.readLineHelper);
			this.fetchHelper = builder.fetchHelper;
			this.functions = builder.functions;
			this.lambdaDecls = builder.lambdaDecls;
			this.indirectCallArities = builder.indirectCallArities;
			this.nextFuncId = builder.nextFuncId;
			this.numOps = builder.numOps;
			this.mathOps = builder.mathOps;
			this.systemOps = builder.systemOps;
		}

		static Builder builder() {
			return new Builder();
		}

		static final class Builder {

			private @Nullable ConstantPool cp;

			private @Nullable FieldrefConstant systemOut;

			private @Nullable MethodrefConstant printlnStr;

			private @Nullable MethodrefConstant lispToString;

			private @Nullable MethodrefConstant printStr;

			private @Nullable MethodrefConstant printlnVoid;

			private @Nullable MethodrefConstant lispToDisplayString;

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

			private @Nullable MethodrefConstant appendMethod;

			private @Nullable MethodrefConstant mathAbsLong;

			private @Nullable MethodrefConstant mathAbsDouble;

			private @Nullable MethodrefConstant mathMinLong;

			private @Nullable MethodrefConstant mathMinDouble;

			private @Nullable MethodrefConstant mathMaxLong;

			private @Nullable MethodrefConstant mathMaxDouble;

			private @Nullable MethodrefConstant mathFloor;

			private @Nullable MethodrefConstant mathCeil;

			private @Nullable MethodrefConstant mathRint;

			private @Nullable MethodrefConstant objectEquals;

			private @Nullable MethodrefConstant readLineHelper;

			private @Nullable MethodrefConstant fetchHelper;

			private Map<String, FunctionInfo> functions = Map.of();

			private List<LambdaInfo> lambdaDecls = new ArrayList<>();

			private Set<Integer> indirectCallArities = new HashSet<>();

			private int[] nextFuncId = new int[1];

			private boolean dynamic = false;

			private String className = "";

			private Set<String> userDefunNames = Set.of();

			private Map<String, MethodrefConstant> numOps = Map.of();

			private Map<String, MethodrefConstant> mathOps = Map.of();

			private Map<String, MethodrefConstant> systemOps = Map.of();

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

			Builder printStr(MethodrefConstant printStr) {
				this.printStr = printStr;
				return this;
			}

			Builder printlnVoid(MethodrefConstant printlnVoid) {
				this.printlnVoid = printlnVoid;
				return this;
			}

			Builder lispToDisplayString(MethodrefConstant lispToDisplayString) {
				this.lispToDisplayString = lispToDisplayString;
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

			Builder appendMethod(MethodrefConstant appendMethod) {
				this.appendMethod = appendMethod;
				return this;
			}

			Builder mathAbsLong(MethodrefConstant mathAbsLong) {
				this.mathAbsLong = mathAbsLong;
				return this;
			}

			Builder mathAbsDouble(MethodrefConstant mathAbsDouble) {
				this.mathAbsDouble = mathAbsDouble;
				return this;
			}

			Builder mathMinLong(MethodrefConstant mathMinLong) {
				this.mathMinLong = mathMinLong;
				return this;
			}

			Builder mathMinDouble(MethodrefConstant mathMinDouble) {
				this.mathMinDouble = mathMinDouble;
				return this;
			}

			Builder mathMaxLong(MethodrefConstant mathMaxLong) {
				this.mathMaxLong = mathMaxLong;
				return this;
			}

			Builder mathMaxDouble(MethodrefConstant mathMaxDouble) {
				this.mathMaxDouble = mathMaxDouble;
				return this;
			}

			Builder mathFloor(MethodrefConstant mathFloor) {
				this.mathFloor = mathFloor;
				return this;
			}

			Builder mathCeil(MethodrefConstant mathCeil) {
				this.mathCeil = mathCeil;
				return this;
			}

			Builder mathRint(MethodrefConstant mathRint) {
				this.mathRint = mathRint;
				return this;
			}

			Builder objectEquals(MethodrefConstant objectEquals) {
				this.objectEquals = objectEquals;
				return this;
			}

			Builder readLineHelper(MethodrefConstant readLineHelper) {
				this.readLineHelper = readLineHelper;
				return this;
			}

			Builder fetchHelper(@Nullable MethodrefConstant fetchHelper) {
				this.fetchHelper = fetchHelper;
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

			Builder dynamic(boolean dynamic) {
				this.dynamic = dynamic;
				return this;
			}

			Builder className(String className) {
				this.className = className;
				return this;
			}

			Builder userDefunNames(Set<String> userDefunNames) {
				this.userDefunNames = userDefunNames;
				return this;
			}

			Builder numOps(Map<String, MethodrefConstant> numOps) {
				this.numOps = numOps;
				return this;
			}

			Builder mathOps(Map<String, MethodrefConstant> mathOps) {
				this.mathOps = mathOps;
				return this;
			}

			Builder systemOps(Map<String, MethodrefConstant> systemOps) {
				this.systemOps = systemOps;
				return this;
			}

			Ctx build() {
				return new Ctx(this);
			}

		}

		MethodrefConstant numOp(String key) {
			return Objects.requireNonNull(this.numOps.get(key), () -> "Unknown numeric helper: " + key);
		}

		MethodrefConstant mathOp(String key) {
			return Objects.requireNonNull(this.mathOps.get(key), () -> "Unknown math helper: " + key);
		}

		MethodrefConstant systemOp(String key) {
			return Objects.requireNonNull(this.systemOps.get(key), () -> "Unknown system helper: " + key);
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
