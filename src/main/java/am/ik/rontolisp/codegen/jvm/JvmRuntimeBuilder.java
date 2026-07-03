package am.ik.rontolisp.codegen.jvm;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Builds JVM bytecode for runtime helper methods: dispatch, _lispToString, and
 * _consToString.
 */
final class JvmRuntimeBuilder {

	private JvmRuntimeBuilder() {
	}

	static JvmLispCompiler.DispatchMethod buildDispatchMethod(int arity,
			Map<String, JvmLispCompiler.FunctionInfo> functions, List<JvmLispCompiler.LambdaInfo> lambdaDecls,
			List<JvmLispCompiler.FunctionInfo> lambdaFuncInfos, ConstantPool cp, ClassConstant thisClass,
			ClassConstant objectArrayClass, ClassConstant integerClass, MethodrefConstant integerValue,
			ClassConstant objectClass, @org.jspecify.annotations.Nullable MethodrefConstant applyRef) {
		String name = "_invoke_" + arity;
		// Descriptor: (Object funcval, Object a0, ..., Object aN-1) -> Object
		String desc = "(" + "Ljava/lang/Object;".repeat(arity + 1) + ")Ljava/lang/Object;";
		Utf8Constant nameUtf8 = cp.addUtf8(name);
		Utf8Constant descUtf8 = cp.addUtf8(desc);
		// Params: slot 0=funcval, slot 1..arity=args
		// Extra locals: fvSlot=arity+1 (Object[] fv), idSlot=arity+2 (int id),
		// restSlot=arity+3 (arg list for the _apply fallback)
		int fvSlot = arity + 1;
		int idSlot = arity + 2;
		int restSlot = arity + 3;
		int maxLocals = arity + 4;
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
		// Interpreted closure (funcId == -1, created by the eval runtime's lambda):
		// delegate to _apply with the arguments collected into a cons list
		if (applyRef != null) {
			code.add(Opcode.ILOAD);
			code.add(idSlot);
			code.add(Opcode.ICONST_M1);
			int ifPos = code.size();
			code.add(Opcode.IF_ICMPNE);
			emitU2(code, 0);
			code.add(Opcode.ACONST_NULL);
			code.add(Opcode.ASTORE);
			code.add(restSlot);
			for (int j = arity - 1; j >= 0; j--) {
				code.add(Opcode.ICONST_2);
				code.add(Opcode.ANEWARRAY);
				emitU2(code, objectClass.index());
				code.add(Opcode.DUP);
				code.add(Opcode.ICONST_0);
				code.add(Opcode.ALOAD);
				code.add(j + 1);
				code.add(Opcode.AASTORE);
				code.add(Opcode.DUP);
				code.add(Opcode.ICONST_1);
				code.add(Opcode.ALOAD);
				code.add(restSlot);
				code.add(Opcode.AASTORE);
				code.add(Opcode.ASTORE);
				code.add(restSlot);
			}
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.ALOAD);
			code.add(restSlot);
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, applyRef.index());
			code.add(Opcode.ARETURN);
			patchBranch(code, ifPos, code.size());
		}
		// Generate if-else chain for each function with matching arity. A variadic
		// function (physical params = required + rest list) matches every dispatch
		// arity >= required; its case links the surplus args into a cons list.
		// Named functions (non-closure)
		for (Map.Entry<String, JvmLispCompiler.FunctionInfo> entry : functions.entrySet()) {
			JvmLispCompiler.FunctionInfo fi = entry.getValue();
			if (!fi.isClosure() && dispatchMatches(fi.paramCount(), fi.variadic(), arity)) {
				emitDispatchCase(code, fi, arity, idSlot, restSlot, -1, objectClass);
			}
		}
		// Lambda functions (closure): the closure env is passed as the first argument
		for (int i = 0; i < lambdaDecls.size(); i++) {
			JvmLispCompiler.LambdaInfo lambda = lambdaDecls.get(i);
			JvmLispCompiler.FunctionInfo fi = lambdaFuncInfos.get(i);
			if (dispatchMatches(lambda.paramNames().size(), lambda.variadic(), arity)) {
				emitDispatchCase(code, fi, arity, idSlot, restSlot, fvSlot, objectClass);
			}
		}
		// Default: return null
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		return new JvmLispCompiler.DispatchMethod(nameUtf8, descUtf8, code, maxLocals);
	}

	private static boolean dispatchMatches(int paramCount, boolean variadic, int arity) {
		return variadic ? arity >= paramCount - 1 : paramCount == arity;
	}

	// Emits one "if (id == funcId) { ...; return f(...); }" dispatch case. For a
	// variadic target the args beyond the required count are linked into a cons list
	// (built in restSlot) passed as the trailing rest parameter; fvSlot >= 0 marks a
	// closure whose env array is passed first.
	private static void emitDispatchCase(List<Integer> code, JvmLispCompiler.FunctionInfo fi, int arity, int idSlot,
			int restSlot, int fvSlot, ClassConstant objectClass) {
		int required = fi.variadic() ? fi.paramCount() - 1 : fi.paramCount();
		code.add(Opcode.ILOAD);
		code.add(idSlot);
		emitIntConstStatic(code, fi.funcId());
		int ifPos = code.size();
		code.add(Opcode.IF_ICMPNE);
		emitU2(code, 0);
		if (fi.variadic()) {
			// rest = null; for (j = arity-1 .. required) rest = new Object[]{a_j, rest}
			code.add(Opcode.ACONST_NULL);
			code.add(Opcode.ASTORE);
			code.add(restSlot);
			for (int j = arity - 1; j >= required; j--) {
				code.add(Opcode.ICONST_2);
				code.add(Opcode.ANEWARRAY);
				emitU2(code, objectClass.index());
				code.add(Opcode.DUP);
				code.add(Opcode.ICONST_0);
				code.add(Opcode.ALOAD);
				code.add(j + 1);
				code.add(Opcode.AASTORE);
				code.add(Opcode.DUP);
				code.add(Opcode.ICONST_1);
				code.add(Opcode.ALOAD);
				code.add(restSlot);
				code.add(Opcode.AASTORE);
				code.add(Opcode.ASTORE);
				code.add(restSlot);
			}
		}
		if (fvSlot >= 0) {
			code.add(Opcode.ALOAD);
			code.add(fvSlot);
		}
		for (int i = 0; i < required; i++) {
			code.add(Opcode.ALOAD);
			code.add(i + 1);
		}
		if (fi.variadic()) {
			code.add(Opcode.ALOAD);
			code.add(restSlot);
		}
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, fi.methodref().index());
		code.add(Opcode.ARETURN);
		patchBranch(code, ifPos, code.size());
	}

	/**
	 * Builds bytecode for _lispToString. Handles Long, Double, String, BigInteger[]
	 * (ratio), Object[] (cons or function), and fallback toString.
	 */
	static List<Integer> buildLispToStringBody(ClassConstant longClass, ClassConstant doubleClass,
			ClassConstant stringClass, ClassConstant objectArrayClass, ClassConstant integerClass,
			MethodrefConstant longToString, MethodrefConstant doubleToString, MethodrefConstant objectToString,
			MethodrefConstant consToStringMethod, ConstantPool.StringConstant nilStr,
			ConstantPool.StringConstant funcStr, ClassConstant ratioArrayClass, MethodrefConstant stringConcat,
			ConstantPool.StringConstant slashStr, ClassConstant characterClass, MethodrefConstant charValue,
			MethodrefConstant charPrin1Method, @org.jspecify.annotations.Nullable ClassConstant arrayListClass,
			@org.jspecify.annotations.Nullable MethodrefConstant arrayToStringMethod,
			@org.jspecify.annotations.Nullable JavaPrint javaPrint,
			@org.jspecify.annotations.Nullable PromisePrint promisePrint) {
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
		// if (val instanceof CompletableFuture) return "#<PROMISE>"; (only when the
		// program can create promises)
		emitPromiseBranch(code, promisePrint);
		// if (val instanceof ArrayList) return _arrayToString(val); (only when arrays
		// used)
		emitArrayBranch(code, arrayListClass, arrayToStringMethod);
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

		// if (val instanceof Double) return ((Double)val).toString();
		patchBranch(code, ifNotLongPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, doubleClass.index());
		int ifNotDoublePos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, doubleClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, doubleToString.index());
		code.add(Opcode.ARETURN);

		// if (val instanceof String) return (String)val;
		patchBranch(code, ifNotDoublePos, code.size());
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

		// if (val instanceof Character) return _charPrin1(((Character)val).charValue());
		patchBranch(code, ifNotStringPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, characterClass.index());
		int ifNotCharPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, characterClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, charValue.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, charPrin1Method.index());
		code.add(Opcode.ARETURN);

		// if (val instanceof BigInteger[]) -> "num/den" (must precede the Object[]
		// check: a ratio is also an Object[])
		patchBranch(code, ifNotCharPos, code.size());
		int ifNotRatioPos = emitRatioToString(code, ratioArrayClass, objectToString, stringConcat, slashStr);

		// if (val instanceof Object[])
		patchBranch(code, ifNotRatioPos, code.size());
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

		// "#<java class>" for a wrapped host object (java: interop), then val.toString()
		patchBranch(code, ifNotArrayPos, code.size());
		emitDefaultTail(code, objectToString, javaPrint);

		return code;
	}

	/**
	 * Builds {@code _charPrin1(char) -> String}: the readable {@code #\name} form of a
	 * character (a standard name for the common non-graphic characters, otherwise the
	 * bare glyph). Used by {@code _lispToString} (prin1) to print a {@code Character}.
	 */
	static List<Integer> buildCharPrin1Body(ConstantPool cp, MethodrefConstant stringConcat,
			MethodrefConstant stringValueOfChar) {
		JvmAsm a = new JvmAsm();
		emitCharNameCase(a, cp, ' ', "#\\Space");
		emitCharNameCase(a, cp, '\n', "#\\Newline");
		emitCharNameCase(a, cp, '\t', "#\\Tab");
		emitCharNameCase(a, cp, '\r', "#\\Return");
		emitCharNameCase(a, cp, '\f', "#\\Page");
		emitCharNameCase(a, cp, '\b', "#\\Backspace");
		emitCharNameCase(a, cp, 0, "#\\Nul");
		emitCharNameCase(a, cp, 127, "#\\Rubout");
		// default: "#\".concat(String.valueOf(c))
		a.ldcString(cp.addString("#\\"));
		a.iload(0);
		a.invokestatic(stringValueOfChar);
		a.invokevirtual(stringConcat);
		a.areturn();
		return a.finish();
	}

	private static void emitCharNameCase(JvmAsm a, ConstantPool cp, int ch, String result) {
		int next = a.label();
		a.iload(0);
		a.iconst(ch);
		a.branch(Opcode.IF_ICMPNE, next);
		a.ldcString(cp.addString(result));
		a.areturn();
		a.bind(next);
	}

	// Emits the ratio branch of _lispToString/_lispToDisplayString: if the value in
	// slot 0 is a BigInteger[], returns numerator + "/" + denominator. Returns the
	// branch position to patch to the next type check.
	private static int emitRatioToString(List<Integer> code, ClassConstant ratioArrayClass,
			MethodrefConstant objectToString, MethodrefConstant stringConcat, ConstantPool.StringConstant slashStr) {
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, ratioArrayClass.index());
		int ifNotRatioPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, ratioArrayClass.index());
		code.add(Opcode.ASTORE_1);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, objectToString.index());
		emitLdc(code, slashStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringConcat.index());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.AALOAD);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, objectToString.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringConcat.index());
		code.add(Opcode.ARETURN);
		return ifNotRatioPos;
	}

	static List<Integer> buildConsToStringBody(ClassConstant objectArrayClass, ClassConstant stringBuilderClass,
			MethodrefConstant sbInitStr, MethodrefConstant sbAppendStr, MethodrefConstant sbToString,
			MethodrefConstant lispToStringMethod, ConstantPool.StringConstant openParenStr,
			ConstantPool.StringConstant closeParenStr, ConstantPool.StringConstant spaceStr,
			ConstantPool.StringConstant dotStr, ClassConstant ratioArrayClass) {
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
		// A ratio (BigInteger[]) is also an Object[]; treat it as an improper tail
		// (e.g. (1 . 1/2)) rather than walking into it as a cons cell.
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, ratioArrayClass.index());
		int ifRatioTailPos = code.size();
		code.add(Opcode.IFNE);
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
		patchBranch(code, ifRatioTailPos, code.size());
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

	/**
	 * Builds bytecode for _lispToDisplayString. Same as _lispToString but strips quotes
	 * from strings (charAt(0)=='"' -> substring(1, length-1)).
	 */
	static List<Integer> buildLispToDisplayStringBody(ClassConstant longClass, ClassConstant doubleClass,
			ClassConstant stringClass, ClassConstant objectArrayClass, ClassConstant integerClass,
			MethodrefConstant longToString, MethodrefConstant doubleToString, MethodrefConstant objectToString,
			MethodrefConstant consToDisplayStringMethod, ConstantPool.StringConstant nilStr,
			ConstantPool.StringConstant funcStr, MethodrefConstant stringCharAt, MethodrefConstant stringLength,
			MethodrefConstant stringSubstring, ClassConstant ratioArrayClass, MethodrefConstant stringConcat,
			ConstantPool.StringConstant slashStr, ClassConstant characterClass, MethodrefConstant charValue,
			MethodrefConstant stringValueOfChar, @org.jspecify.annotations.Nullable ClassConstant arrayListClass,
			@org.jspecify.annotations.Nullable MethodrefConstant arrayToDisplayStringMethod,
			@org.jspecify.annotations.Nullable JavaPrint javaPrint,
			@org.jspecify.annotations.Nullable PromisePrint promisePrint) {
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
		// if (val instanceof CompletableFuture) return "#<PROMISE>"; (promises only)
		emitPromiseBranch(code, promisePrint);
		// if (val instanceof ArrayList) return _arrayToDisplayString(val); (arrays only)
		emitArrayBranch(code, arrayListClass, arrayToDisplayStringMethod);
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

		// if (val instanceof Double) return ((Double)val).toString();
		patchBranch(code, ifNotLongPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, doubleClass.index());
		int ifNotDoublePos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, doubleClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, doubleToString.index());
		code.add(Opcode.ARETURN);

		// if (val instanceof String) -> strip quotes if leading '"'
		patchBranch(code, ifNotDoublePos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, stringClass.index());
		int ifNotStringPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, stringClass.index());
		code.add(Opcode.ASTORE_1); // store string in slot 1
		// check charAt(0) == '"'
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringCharAt.index());
		emitIntConstStatic(code, 34); // '"' = 34
		int ifNotQuotePos = code.size();
		code.add(Opcode.IF_ICMPNE);
		emitU2(code, 0);
		// It's a quoted string: return substring(1, length-1)
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringLength.index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ISUB);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringSubstring.index());
		code.add(Opcode.ARETURN);
		// Not a quoted string, return as-is
		patchBranch(code, ifNotQuotePos, code.size());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ARETURN);

		// if (val instanceof Character) return
		// String.valueOf(((Character)val).charValue());
		patchBranch(code, ifNotStringPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, characterClass.index());
		int ifNotCharPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, characterClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, charValue.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, stringValueOfChar.index());
		code.add(Opcode.ARETURN);

		// if (val instanceof BigInteger[]) -> "num/den" (must precede the Object[]
		// check: a ratio is also an Object[])
		patchBranch(code, ifNotCharPos, code.size());
		int ifNotRatioPos = emitRatioToString(code, ratioArrayClass, objectToString, stringConcat, slashStr);

		// if (val instanceof Object[])
		patchBranch(code, ifNotRatioPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, objectArrayClass.index());
		int ifNotArrayPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
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
		emitLdc(code, funcStr.index());
		code.add(Opcode.ARETURN);
		patchBranch(code, ifEmptyPos, code.size());
		patchBranch(code, ifNotFuncPos, code.size());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, consToDisplayStringMethod.index());
		code.add(Opcode.ARETURN);

		// "#<java class>" for a wrapped host object (java: interop), then val.toString()
		patchBranch(code, ifNotArrayPos, code.size());
		emitDefaultTail(code, objectToString, javaPrint);

		return code;
	}

	/**
	 * Builds bytecode for _consToDisplayString. Same as _consToString but calls
	 * _lispToDisplayString recursively.
	 */
	static List<Integer> buildConsToDisplayStringBody(ClassConstant objectArrayClass, ClassConstant stringBuilderClass,
			MethodrefConstant sbInitStr, MethodrefConstant sbAppendStr, MethodrefConstant sbToString,
			MethodrefConstant lispToDisplayStringMethod, ConstantPool.StringConstant openParenStr,
			ConstantPool.StringConstant closeParenStr, ConstantPool.StringConstant spaceStr,
			ConstantPool.StringConstant dotStr, ClassConstant ratioArrayClass) {
		return buildConsToStringBody(objectArrayClass, stringBuilderClass, sbInitStr, sbAppendStr, sbToString,
				lispToDisplayStringMethod, openParenStr, closeParenStr, spaceStr, dotStr, ratioArrayClass);
	}

	/**
	 * Builds bytecode for _append(Object a, Object b). If a is null, returns b.
	 * Otherwise, creates new Object[]{a[0], _append(a[1], b)}.
	 */
	static List<Integer> buildAppendBody(ClassConstant objectArrayClass, ClassConstant objectClass,
			MethodrefConstant appendMethod) {
		List<Integer> code = new ArrayList<>();
		// if (a == null) return b;
		code.add(Opcode.ALOAD_0);
		int ifNonnullPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ARETURN);
		// a is non-null: cast to Object[]
		patchBranch(code, ifNonnullPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(Opcode.ASTORE_2);
		// new Object[2]
		code.add(Opcode.ICONST_2);
		code.add(Opcode.ANEWARRAY);
		emitU2(code, objectClass.index());
		// arr[0] = a[0]
		code.add(Opcode.DUP);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		code.add(Opcode.AASTORE);
		// arr[1] = _append(a[1], b)
		code.add(Opcode.DUP);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.AALOAD);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, appendMethod.index());
		code.add(Opcode.AASTORE);
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * Builds bytecode for _readLine helper. Lazily initializes static _stdinReader field,
	 * reads a line, and wraps it with '"' prefix/suffix for the internal string format.
	 * Returns null for EOF.
	 */
	static List<Integer> buildReadLineBody(ClassConstant bufferedReaderClass, ClassConstant inputStreamReaderClass,
			MethodrefConstant brInit, MethodrefConstant brReadLine, MethodrefConstant isrInit,
			ConstantPool.FieldrefConstant systemIn, ConstantPool.FieldrefConstant stdinReaderField,
			ConstantPool.StringConstant quoteStr, MethodrefConstant stringConcat) {
		List<Integer> code = new ArrayList<>();
		// if (_stdinReader == null)
		code.add(Opcode.GETSTATIC);
		emitU2(code, stdinReaderField.index());
		int ifNonnullPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		// _stdinReader = new BufferedReader(new InputStreamReader(System.in))
		code.add(Opcode.NEW);
		emitU2(code, bufferedReaderClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.NEW);
		emitU2(code, inputStreamReaderClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.GETSTATIC);
		emitU2(code, systemIn.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, isrInit.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, brInit.index());
		code.add(Opcode.PUTSTATIC);
		emitU2(code, stdinReaderField.index());
		// end if
		patchBranch(code, ifNonnullPos, code.size());
		// String line = _stdinReader.readLine();
		code.add(Opcode.GETSTATIC);
		emitU2(code, stdinReaderField.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, brReadLine.index());
		code.add(Opcode.ASTORE_0);
		// if (line == null) return null;
		code.add(Opcode.ALOAD_0);
		int ifNotNullPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		// return "\"" + line + "\""
		patchBranch(code, ifNotNullPos, code.size());
		emitLdc(code, quoteStr.index());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringConcat.index());
		emitLdc(code, quoteStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringConcat.index());
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * Constant-pool references for printing a wrapped {@code java:} host object as
	 * {@code #<java class.Name>} (interpreter parity), threaded into the two
	 * lisp-to-string builders only when the program uses {@code java:} interop.
	 * {@code hashMapClass} is non-null only when the program also uses hash tables, so a
	 * Lisp hash table (a {@code HashMap} at runtime) keeps its plain {@code toString}
	 * printing instead of being mistaken for a host object.
	 */
	record JavaPrint(ClassConstant bigIntegerClass, @org.jspecify.annotations.Nullable ClassConstant hashMapClass,
			MethodrefConstant objectGetClass, MethodrefConstant classGetName, MethodrefConstant stringConcat,
			ConstantPool.StringConstant prefix, ConstantPool.StringConstant suffix) {
	}

	/**
	 * Constant-pool references for printing a promise (a {@code CompletableFuture} at
	 * runtime) as {@code #<PROMISE>} (interpreter parity), threaded into the two
	 * lisp-to-string builders only when the program can create promises
	 * ({@code rontolisp:fetch} / {@code rontolisp:then}).
	 */
	record PromisePrint(ClassConstant futureClass, ConstantPool.StringConstant promiseStr) {
	}

	// Emits "if (val instanceof CompletableFuture) return "#<PROMISE>";" at the current
	// position. A no-op when the program cannot create promises, keeping the branch out
	// of promise-free programs.
	private static void emitPromiseBranch(List<Integer> code,
			@org.jspecify.annotations.Nullable PromisePrint promisePrint) {
		if (promisePrint == null) {
			return;
		}
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, promisePrint.futureClass().index());
		int skip = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		emitLdc(code, promisePrint.promiseStr().index());
		code.add(Opcode.ARETURN);
		patchBranch(code, skip, code.size());
	}

	/**
	 * Emits the final fallback of {@code _lispToString}/{@code _lispToDisplayString}:
	 * plain {@code val.toString()}, preceded -- when {@code java:} interop is in use --
	 * by a {@code #<java class.Name>} branch for wrapped host objects. {@code
	 * BigInteger} (a promoted Lisp integer) and, when hash tables are used,
	 * {@code HashMap} still fall through to {@code toString()}.
	 */
	private static void emitDefaultTail(List<Integer> code, MethodrefConstant objectToString,
			@org.jspecify.annotations.Nullable JavaPrint javaPrint) {
		if (javaPrint != null) {
			List<Integer> toStringBranches = new ArrayList<>();
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.INSTANCEOF);
			emitU2(code, javaPrint.bigIntegerClass().index());
			toStringBranches.add(code.size());
			code.add(Opcode.IFNE);
			emitU2(code, 0);
			if (javaPrint.hashMapClass() != null) {
				code.add(Opcode.ALOAD_0);
				code.add(Opcode.INSTANCEOF);
				emitU2(code, javaPrint.hashMapClass().index());
				toStringBranches.add(code.size());
				code.add(Opcode.IFNE);
				emitU2(code, 0);
			}
			// return "#<java ".concat(val.getClass().getName()).concat(">");
			emitLdc(code, javaPrint.prefix().index());
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, javaPrint.objectGetClass().index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, javaPrint.classGetName().index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, javaPrint.stringConcat().index());
			emitLdc(code, javaPrint.suffix().index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, javaPrint.stringConcat().index());
			code.add(Opcode.ARETURN);
			for (int branch : toStringBranches) {
				patchBranch(code, branch, code.size());
			}
		}
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, objectToString.index());
		code.add(Opcode.ARETURN);
	}

	static void emitU2(List<Integer> code, int value) {
		byte[] bytes = ByteBuffer.allocate(2).putShort((short) value).array();
		code.add((int) bytes[0]);
		code.add((int) bytes[1]);
	}

	// Emits "if (val instanceof ArrayList) return arrayToString(val);" at the current
	// position, used by both the prin1 and princ string builders. A no-op when arrays are
	// not used (both args null), keeping the branch out of array-free programs.
	private static void emitArrayBranch(List<Integer> code,
			@org.jspecify.annotations.Nullable ClassConstant arrayListClass,
			@org.jspecify.annotations.Nullable MethodrefConstant arrayToStringMethod) {
		if (arrayListClass == null || arrayToStringMethod == null) {
			return;
		}
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, arrayListClass.index());
		int ifNotArrayPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, arrayToStringMethod.index());
		code.add(Opcode.ARETURN);
		patchBranch(code, ifNotArrayPos, code.size());
	}

	static void emitLdc(List<Integer> code, int cpIndex) {
		if (cpIndex <= 255) {
			code.add(Opcode.LDC);
			code.add(cpIndex);
		}
		else {
			code.add(Opcode.LDC_W);
			emitU2(code, cpIndex);
		}
	}

	static void emitIntConstStatic(List<Integer> code, int value) {
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

	static void patchBranch(List<Integer> code, int branchPos, int targetPos) {
		int offset = targetPos - branchPos;
		byte[] bytes = ByteBuffer.allocate(2).putShort((short) offset).array();
		code.set(branchPos + 1, (int) bytes[0]);
		code.set(branchPos + 2, (int) bytes[1]);
	}

}
