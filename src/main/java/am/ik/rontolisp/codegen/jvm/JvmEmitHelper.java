package am.ik.rontolisp.codegen.jvm;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;

/**
 * Shared helper methods for JVM bytecode emission used across all expression compilers.
 */
final class JvmEmitHelper {

	private JvmEmitHelper() {
	}

	static void compileLong(long value, JvmLispCompiler.Ctx ctx) {
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

	static void compileDouble(double value, JvmLispCompiler.Ctx ctx) {
		// The raw-bits guard keeps -0.0 out of the DCONST_0 peephole (-0.0 == 0.0
		// in Java), mirroring JvmQuoteCompiler.emitRawDouble.
		if (value == 0.0 && Double.doubleToRawLongBits(value) == 0L) {
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

	static void compileBigInteger(java.math.BigInteger value, JvmLispCompiler.Ctx ctx) {
		ConstantPool.ClassConstant bigClass = ctx.cp.addClass(ctx.cp.addUtf8("java/math/BigInteger"));
		ConstantPool.MethodrefConstant ctor = ctx.cp.addMethodref(bigClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("<init>"), ctx.cp.addUtf8("(Ljava/lang/String;)V")));
		ConstantPool.StringConstant sc = ctx.cp.addString(value.toString());
		ctx.emit(Opcode.NEW);
		ctx.emitU2(bigClass.index());
		ctx.emit(Opcode.DUP);
		if (sc.index() <= 255) {
			ctx.emit(Opcode.LDC);
			ctx.emit(sc.index());
		}
		else {
			ctx.emit(Opcode.LDC_W);
			ctx.emitU2(sc.index());
		}
		ctx.emit(Opcode.INVOKESPECIAL);
		ctx.emitU2(ctor.index());
	}

	/**
	 * Compiles a ratio literal to its runtime representation: a normalized
	 * {@code BigInteger[2]} of numerator and denominator.
	 */
	static void compileRatio(am.ik.rontolisp.LispRatio value, JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(bigIntegerClass(ctx).index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		compileBigInteger(value.numerator(), ctx);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_1);
		compileBigInteger(value.denominator(), ctx);
		ctx.emit(Opcode.AASTORE);
	}

	/** The {@code BigInteger[]} (ratio runtime representation) class constant. */
	static ConstantPool.ClassConstant ratioArrayClass(JvmLispCompiler.Ctx ctx) {
		return ctx.cp.addClass(ctx.cp.addUtf8("[Ljava/math/BigInteger;"));
	}

	/**
	 * Compiles the Lisp boolean true. It is the symbol {@code t} (represented at runtime
	 * as the bare String {@code "t"}, like any other symbol), so it prints as {@code t}
	 * and is {@code eq} to a quoted {@code 't}, matching the interpreter.
	 */
	static void compileTrue(JvmLispCompiler.Ctx ctx) {
		compileStringLiteral("t", ctx);
	}

	static void compileStringLiteral(String value, JvmLispCompiler.Ctx ctx) {
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

	/** The {@code java/math/BigInteger} class constant. */
	static ConstantPool.ClassConstant bigIntegerClass(JvmLispCompiler.Ctx ctx) {
		return ctx.cp.addClass(ctx.cp.addUtf8("java/math/BigInteger"));
	}

	/**
	 * The {@code java/lang/Character} class constant (the char runtime representation).
	 */
	static ConstantPool.ClassConstant characterClass(JvmLispCompiler.Ctx ctx) {
		return ctx.cp.addClass(ctx.cp.addUtf8("java/lang/Character"));
	}

	/** A {@code java.lang.Character} method reference. */
	static ConstantPool.MethodrefConstant characterMethod(JvmLispCompiler.Ctx ctx, String name, String desc) {
		return ctx.cp.addMethodref(characterClass(ctx),
				ctx.cp.addNameAndType(ctx.cp.addUtf8(name), ctx.cp.addUtf8(desc)));
	}

	/**
	 * Compiles a character literal to its runtime representation, a boxed
	 * {@code java.lang.Character}. The code point is narrowed to a 16-bit char (the JVM
	 * char range), matching the BMP coverage of the other backends.
	 */
	static void compileCharLiteral(int codePoint, JvmLispCompiler.Ctx ctx) {
		emitIntConst(ctx, codePoint & 0xFFFF);
		ctx.emit(Opcode.I2C);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(characterMethod(ctx, "valueOf", "(C)Ljava/lang/Character;").index());
	}

	/** A {@code java.math.BigInteger} instance-method reference. */
	static ConstantPool.MethodrefConstant bigIntegerMethod(JvmLispCompiler.Ctx ctx, String name, String desc) {
		return ctx.cp.addMethodref(bigIntegerClass(ctx),
				ctx.cp.addNameAndType(ctx.cp.addUtf8(name), ctx.cp.addUtf8(desc)));
	}

	/** A {@code java.lang.String} instance-method reference. */
	static ConstantPool.MethodrefConstant stringMethod(JvmLispCompiler.Ctx ctx, String name, String desc) {
		return ctx.cp.addMethodref(ctx.stringClass, ctx.cp.addNameAndType(ctx.cp.addUtf8(name), ctx.cp.addUtf8(desc)));
	}

	/**
	 * Coerces the {@code Object} on the stack (Long or BigInteger) to a
	 * {@code BigInteger}.
	 */
	static void toBigInteger(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.BIG_OP).index());
	}

	/** Normalizes the {@code BigInteger} on the stack to a {@code Long} when it fits. */
	static void normalizeBigInteger(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.NORM_OP).index());
	}

	static void unboxLong(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.longClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.longValue.index());
	}

	static void boxLong(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.longValueOf.index());
	}

	static void unboxDouble(JvmLispCompiler.Ctx ctx) {
		// _dbl coerces Long/BigInteger/Double and ratios (BigInteger[]) to a Double, so
		// float contagion also works when a ratio flows into a double-literal operation.
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.DBL).index());
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.numberClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.numberDoubleValue.index());
	}

	static void boxDouble(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.doubleValueOf.index());
	}

	static void emitIntConst(JvmLispCompiler.Ctx ctx, int value) {
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
	 * Converts an i32 (0=false, non-0=true) on the JVM stack into a Lisp boolean
	 * (null=nil or the symbol {@code t}).
	 */
	static void emitBoolFromInt(JvmLispCompiler.Ctx ctx) {
		int ifPos = ctx.code.size();
		ctx.emit(Opcode.IFNE);
		ctx.emitU2(0);
		ctx.emit(Opcode.ACONST_NULL);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		patchBranch(ctx, ifPos, ctx.code.size());
		compileTrue(ctx);
		patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

	static void patchBranch(JvmLispCompiler.Ctx ctx, int branchPos, int targetPos) {
		JvmRuntimeBuilder.patchBranch(ctx.code, branchPos, targetPos);
	}

	/**
	 * Emits a list-type guard for the {@code map*} family over the value in
	 * {@code listSlot}: if the value is neither null (nil) nor an {@code Object[]} (a
	 * cons), a {@link RuntimeException} carrying {@code message} is thrown. This matches
	 * the interpreter, which signals an error rather than silently treating a non-list
	 * (e.g. a string) as the empty list. The operand stack is empty at every branch and
	 * at the merge point, so the version-50 verifier accepts it.
	 */
	static void emitRequireListGuard(JvmLispCompiler.Ctx ctx, int listSlot, String message) {
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.objectArrayClass.index());
		int ifIsArrayPos = ctx.code.size();
		ctx.emit(Opcode.IFNE);
		ctx.emitU2(0);
		// Not a list: throw new RuntimeException(message).
		ConstantPool.ClassConstant runtimeEx = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/RuntimeException"));
		ConstantPool.MethodrefConstant ctor = ctx.cp.addMethodref(runtimeEx,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("<init>"), ctx.cp.addUtf8("(Ljava/lang/String;)V")));
		ctx.emit(Opcode.NEW);
		ctx.emitU2(runtimeEx.index());
		ctx.emit(Opcode.DUP);
		compileStringLiteral(message, ctx);
		ctx.emit(Opcode.INVOKESPECIAL);
		ctx.emitU2(ctor.index());
		ctx.emit(Opcode.ATHROW);
		// ok: both the nil and the cons case fall through here.
		patchBranch(ctx, ifNullPos, ctx.code.size());
		patchBranch(ctx, ifIsArrayPos, ctx.code.size());
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

}
