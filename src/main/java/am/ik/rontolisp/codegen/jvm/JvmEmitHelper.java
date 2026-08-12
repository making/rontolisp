package am.ik.rontolisp.codegen.jvm;

import am.ik.jvm.ArrayType;
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
	 * Emits the instance exclusion shared by the cons-shaped predicates
	 * ({@code consp}/{@code listp}/{@code atom}): an instance is an {@code Object[]}
	 * carrying its {@code String[]} layout in slot 0, and must NOT answer as a cons. The
	 * value must already be in {@code tempSlot} and known to be a non-ratio
	 * {@code Object[]}. Nothing is emitted when the program cannot build an instance, so
	 * such a program compiles byte-identically to a build that never knew about them.
	 * @param ctx the compilation context
	 * @param tempSlot the local holding the value
	 * @return the branch position to patch to the not-a-cons target, or -1 when no test
	 * was emitted
	 */
	static int emitInstanceExclusion(JvmLispCompiler.Ctx ctx, int tempSlot) {
		if (!ctx.mayUseInstances) {
			return -1;
		}
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tempSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.layoutPool.stringArrayClass(ctx.cp).index());
		int pos = ctx.code.size();
		ctx.emit(Opcode.IFNE);
		ctx.emitU2(0);
		return pos;
	}

	/**
	 * Compiles the Lisp boolean true. It is the symbol {@code t} (represented at runtime
	 * as the bare String {@code "t"}, like any other symbol), so it prints as {@code t}
	 * and is {@code eq} to a quoted {@code 't}, matching the interpreter.
	 */
	static void compileTrue(JvmLispCompiler.Ctx ctx) {
		compileStringLiteral("T", ctx);
	}

	static void compileStringLiteral(String value, JvmLispCompiler.Ctx ctx) {
		// The loaded value is a literal the program can hold at run time, so its
		// spelling is a designator the dispatch gate's name probes must see.
		ctx.spelledLiterals.add(value);
		compileUnspelledLiteral(value, ctx);
	}

	/**
	 * {@link #compileStringLiteral} minus the spelled-literal record: the emission for a
	 * name the COMPILER synthesized ({@code %unspelled-quote}), which must not arm the
	 * funcall-dispatch gate's name probes. See {@code LispNames.UNSPELLED_QUOTE}.
	 * @param value the symbol name (or framed string) to load
	 * @param ctx the compilation context
	 */
	static void compileUnspelledLiteral(String value, JvmLispCompiler.Ctx ctx) {
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
	 * The {@code java/lang/Character} class constant. Used by JDK static helpers
	 * ({@code Character.toUpperCase(int)}, {@code Character.isLetter(int)}, ...); the
	 * runtime CHARACTER representation is a length-1 {@code int[]} whose sole element is
	 * the Unicode code point ({@link #charArrayClass}), not this class.
	 */
	static ConstantPool.ClassConstant characterClass(JvmLispCompiler.Ctx ctx) {
		return ctx.cp.addClass(ctx.cp.addUtf8("java/lang/Character"));
	}

	/**
	 * A {@code java.lang.Character} static method reference (still needed for the JDK
	 * helpers like {@code Character.toUpperCase(int)} / {@code Character.digit(int, int)}
	 * that the char builtins delegate to).
	 */
	static ConstantPool.MethodrefConstant characterMethod(JvmLispCompiler.Ctx ctx, String name, String desc) {
		return ctx.cp.addMethodref(characterClass(ctx),
				ctx.cp.addNameAndType(ctx.cp.addUtf8(name), ctx.cp.addUtf8(desc)));
	}

	/**
	 * The class constant for the runtime CHARACTER representation ({@code int[]}, spelled
	 * {@code [I} in bytecode). A Lisp character on the JVM compile path is a length-1
	 * {@code int[]} holding its Unicode code point; a supplementary code point (above
	 * U+FFFF) fits without truncation and prints as its glyph via
	 * {@link Character#toChars(int)}. Instance and array-class checks against this
	 * constant are the type discriminator ({@code instanceof int[]}), never
	 * {@link #characterClass} which is 16-bit.
	 */
	static ConstantPool.ClassConstant charArrayClass(JvmLispCompiler.Ctx ctx) {
		return ctx.cp.addClass(ctx.cp.addUtf8("[I"));
	}

	/**
	 * Compiles a character literal to its runtime representation, a length-1
	 * {@code int[]} holding the Unicode code point. Uses a temporary local so the boxing
	 * sequence is straight-line (allocate, dup, store [0], leave array on stack). Widens
	 * the previous 16-bit Character representation so a {@code #\U+1F600} literal
	 * survives every downstream op.
	 */
	static void compileCharLiteral(int codePoint, JvmLispCompiler.Ctx ctx) {
		emitIntConst(ctx, codePoint);
		boxCodePoint(ctx);
	}

	/**
	 * Boxes the {@code int} Unicode code point currently on top of the operand stack into
	 * the runtime CHARACTER representation ({@code int[]{cp}}). Uses one transient temp
	 * local so the sequence stays branch-free and self-contained. On entry:
	 * {@code [.., cp:int]}; on exit: {@code [.., int[1]{cp}:ref]}.
	 */
	static void boxCodePoint(JvmLispCompiler.Ctx ctx) {
		int tmp = ctx.allocTemp();
		ctx.emit(Opcode.ISTORE);
		ctx.emit(tmp);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.NEWARRAY);
		ctx.emit(ArrayType.T_INT);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.ILOAD);
		ctx.emit(tmp);
		ctx.emit(Opcode.IASTORE);
	}

	/**
	 * Unboxes a runtime CHARACTER ({@code int[]}) on top of the operand stack to its
	 * Unicode code point ({@code int}). Casts to {@code [I} first so the JVM verifier
	 * sees an int-array reference, then reads {@code arr[0]}. On entry:
	 * {@code [.., ref]}; on exit: {@code [.., cp:int]}.
	 */
	static void unboxCodePoint(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(charArrayClass(ctx).index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.IALOAD);
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

	/**
	 * Pushes an {@code int} constant in its shortest encoding.
	 *
	 * <p>
	 * {@code sipush} takes a SIGNED 16-bit operand, so anything outside
	 * {@code [-32768, 32767]} has to come from the constant pool -- emitting it as a
	 * {@code sipush} truncates and sign-extends silently, producing a class that verifies
	 * and computes the wrong number. The reachable case is a CHARACTER above the BMP
	 * ({@code (string (code-char 128512))}, code point 0x1F600 -&gt; -2560), which the
	 * literal fold routes through here as a folded {@code #\U+1F600} literal; the
	 * counting callers (lambda ids, quoted-vector lengths, slot indices) would need a
	 * program of absurd size to reach it, but they share the same fix.
	 */
	static void emitIntConst(JvmLispCompiler.Ctx ctx, int value) {
		if (value >= 0 && value <= 5) {
			ctx.emit(Opcode.ICONST_0 + value);
		}
		else if (value >= -128 && value <= 127) {
			ctx.emit(Opcode.BIPUSH);
			ctx.emit(value & 0xFF);
		}
		else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
			ctx.emit(Opcode.SIPUSH);
			ctx.emitU2(value);
		}
		else {
			ConstantPool.IntegerConstant constant = ctx.cp.addInteger(value);
			if (constant.index() <= 255) {
				ctx.emit(Opcode.LDC);
				ctx.emit(constant.index());
			}
			else {
				ctx.emit(Opcode.LDC_W);
				ctx.emitU2(constant.index());
			}
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

	/**
	 * Binds a forward branch to its target -- the position about to be emitted. The
	 * operand-stack model adopts the shape the branch jumped with, which is how the merge
	 * points of {@code if}/{@code %block}/the predicate guards stay tracked.
	 */
	static void patchBranch(JvmLispCompiler.Ctx ctx, int branchPos, int targetPos) {
		int offset = targetPos - branchPos;
		if (offset < Short.MIN_VALUE || offset > Short.MAX_VALUE) {
			// Past the signed 16-bit encoding: leave the placeholder bytes and let the
			// per-method BranchRelaxer pass rewrite this branch over a goto_w
			// (.kb/jvm-method-size-limits.md). Only the raw-list patchBranch of the
			// self-budgeted runtime builders still throws.
			ctx.deferredBranches.add(new int[] { branchPos, targetPos });
		}
		else {
			JvmRuntimeBuilder.patchBranch(ctx.code, branchPos, targetPos);
		}
		ctx.stack.reconcile(branchPos, targetPos, ctx.code.size());
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
