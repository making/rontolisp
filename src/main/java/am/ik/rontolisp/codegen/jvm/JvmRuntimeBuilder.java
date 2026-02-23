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
		for (Map.Entry<String, JvmLispCompiler.FunctionInfo> entry : functions.entrySet()) {
			JvmLispCompiler.FunctionInfo fi = entry.getValue();
			if (fi.paramCount() == arity && !fi.isClosure()) {
				code.add(Opcode.ILOAD);
				code.add(idSlot);
				emitIntConstStatic(code, fi.funcId());
				int ifPos = code.size();
				code.add(Opcode.IF_ICMPNE);
				emitU2(code, 0);
				// Load args and call
				for (int i = 0; i < arity; i++) {
					code.add(Opcode.ALOAD);
					code.add(i + 1);
				}
				code.add(Opcode.INVOKESTATIC);
				emitU2(code, fi.methodref().index());
				code.add(Opcode.ARETURN);
				patchBranch(code, ifPos, code.size());
			}
		}
		// Lambda functions (closure)
		for (int i = 0; i < lambdaDecls.size(); i++) {
			JvmLispCompiler.LambdaInfo lambda = lambdaDecls.get(i);
			JvmLispCompiler.FunctionInfo fi = lambdaFuncInfos.get(i);
			if (lambda.paramNames().size() == arity) {
				code.add(Opcode.ILOAD);
				code.add(idSlot);
				emitIntConstStatic(code, fi.funcId());
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
				emitU2(code, fi.methodref().index());
				code.add(Opcode.ARETURN);
				patchBranch(code, ifPos, code.size());
			}
		}
		// Default: return null
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		return new JvmLispCompiler.DispatchMethod(nameUtf8, descUtf8, code, maxLocals);
	}

	/**
	 * Builds bytecode for _lispToString. Handles Long, Double, String, Object[] (cons or
	 * function), and fallback toString.
	 */
	static List<Integer> buildLispToStringBody(ClassConstant longClass, ClassConstant doubleClass,
			ClassConstant stringClass, ClassConstant objectArrayClass, ClassConstant integerClass,
			MethodrefConstant longToString, MethodrefConstant doubleToString, MethodrefConstant objectToString,
			MethodrefConstant consToStringMethod, ConstantPool.StringConstant nilStr,
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

	static List<Integer> buildConsToStringBody(ClassConstant objectArrayClass, ClassConstant stringBuilderClass,
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

	/**
	 * Builds bytecode for _lispToDisplayString. Same as _lispToString but strips quotes
	 * from strings (charAt(0)=='"' -> substring(1, length-1)).
	 */
	static List<Integer> buildLispToDisplayStringBody(ClassConstant longClass, ClassConstant doubleClass,
			ClassConstant stringClass, ClassConstant objectArrayClass, ClassConstant integerClass,
			MethodrefConstant longToString, MethodrefConstant doubleToString, MethodrefConstant objectToString,
			MethodrefConstant consToDisplayStringMethod, ConstantPool.StringConstant nilStr,
			ConstantPool.StringConstant funcStr, MethodrefConstant stringCharAt, MethodrefConstant stringLength,
			MethodrefConstant stringSubstring) {
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

		// if (val instanceof Object[])
		patchBranch(code, ifNotStringPos, code.size());
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

		// return val.toString();
		patchBranch(code, ifNotArrayPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, objectToString.index());
		code.add(Opcode.ARETURN);

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
			ConstantPool.StringConstant dotStr) {
		return buildConsToStringBody(objectArrayClass, stringBuilderClass, sbInitStr, sbAppendStr, sbToString,
				lispToDisplayStringMethod, openParenStr, closeParenStr, spaceStr, dotStr);
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

	static void emitU2(List<Integer> code, int value) {
		byte[] bytes = ByteBuffer.allocate(2).putShort((short) value).array();
		code.add((int) bytes[0]);
		code.add((int) bytes[1]);
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
