package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.jvm.Opcode;
import am.ik.jvm.OperandStack;

/**
 * Shared helper methods for JVM bytecode emission used across all expression compilers.
 */
final class JvmEmitHelper {

	private JvmEmitHelper() {
	}

	/**
	 * Emits an inline loop that leaves one reference as its value, with the enclosing
	 * expression's pending operands spilled to locals around it -- so the loop head, the
	 * target of the backedge, sits at operand stack depth 0.
	 *
	 * <p>
	 * HotSpot can only enter an on-stack-replacement compilation at a backedge whose
	 * operand stack is empty; a loop head under pending operands is refused at every tier
	 * ({@code COMPILE SKIPPED: stack not empty at OSR entry point}), and a method entered
	 * once -- every top-level form, every {@code defun} called once with a long loop
	 * inside -- has no other route into a compiled version, so it runs in the bytecode
	 * interpreter forever. {@link JvmTagbodyCompiler} carries the full reasoning; every
	 * emitter that writes a backedge reachable from expression position goes through here
	 * or does the same bracketing itself.
	 * @param ctx the emission context
	 * @param emitLoop emits the loop; must leave exactly one reference on the stack
	 */
	static void inLoopScope(JvmLispCompiler.Ctx ctx, Runnable emitLoop) {
		JvmLispCompiler.Ctx.Spill spill = enterLoopScope(ctx);
		emitLoop.run();
		if (spill.isEmpty()) {
			return;
		}
		// The loop's value has to end up back ON TOP of the reloaded operands.
		int resultSlot = ctx.allocTemp();
		ctx.body.astore(resultSlot);
		leaveLoopScope(ctx, spill);
		ctx.body.aload(resultSlot);
	}

	/**
	 * Spills the enclosing expression's pending operands so the loop about to be emitted
	 * has its head at operand stack depth 0, and registers the spill so a {@code return}
	 * or {@code go} leaving the loop for an enclosing block reloads that block's operands
	 * from it -- the same bookkeeping {@code handler-case}'s spill uses. Balanced by
	 * {@link #leaveLoopScope}. Used directly by the emitters whose loop leaves no value
	 * of its own to reorder ({@link JvmTagbodyCompiler}, {@link JvmWhileCompiler}: both
	 * push their nil result after the reload).
	 * @param ctx the emission context
	 * @return the spill, empty when nothing was pending -- then the emitted bytes are
	 * unchanged and {@link #leaveLoopScope} is a no-op
	 */
	static JvmLispCompiler.Ctx.Spill enterLoopScope(JvmLispCompiler.Ctx ctx) {
		if (!ctx.hasRoomToSpillOperandStack()) {
			// Out of one-byte local slots. The spill is an optimization, so it declines
			// rather than failing a compile that would otherwise have succeeded.
			return JvmLispCompiler.Ctx.Spill.EMPTY;
		}
		if (ctx.stack.snapshot().contains(OperandStack.Slot.UNINIT)) {
			// A half-constructed object cannot be saved into a local -- the verifier
			// tracks it apart from an ordinary reference -- so the spill is impossible
			// here and the loop head keeps its pending operands. No emitter leaves one
			// live across an argument today (JvmErrorCompiler was the last to), and the
			// corpus check pins that; the fallback keeps a future one compiling rather
			// than failing outright.
			return JvmLispCompiler.Ctx.Spill.EMPTY;
		}
		JvmLispCompiler.Ctx.Spill spill = ctx.spillLoopEntryStack();
		if (!spill.isEmpty()) {
			ctx.spillScopes.push(new JvmLispCompiler.SpillScope(spill, ctx.blockTargets.size()));
		}
		return spill;
	}

	/** Reloads what {@link #enterLoopScope} spilled, and unregisters the spill scope. */
	static void leaveLoopScope(JvmLispCompiler.Ctx ctx, JvmLispCompiler.Ctx.Spill spill) {
		if (spill.isEmpty()) {
			return;
		}
		ctx.spillScopes.pop();
		spill.restore(ctx);
	}

	static void compileLong(long value, JvmLispCompiler.Ctx ctx) {
		emitRawLong(value, ctx);
		ctx.body.invokestatic(ctx.longValueOf.entry());
	}

	/** Pushes the primitive {@code long} (no boxing). */
	static void emitRawLong(long value, JvmLispCompiler.Ctx ctx) {
		if (value == 0) {
			ctx.body.lconst_0();
		}
		else if (value == 1) {
			ctx.body.lconst_1();
		}
		else {
			ConstantPool.LongConstant lc = ctx.cp.addLong(value);
			ctx.body.ldc(lc.entry());
		}
	}

	static void compileDouble(double value, JvmLispCompiler.Ctx ctx) {
		emitRawDouble(value, ctx);
		ctx.body.invokestatic(ctx.doubleValueOf.entry());
	}

	/** Pushes the primitive {@code double} (no boxing). */
	static void emitRawDouble(double value, JvmLispCompiler.Ctx ctx) {
		// The raw-bits guard keeps -0.0 out of the DCONST_0 peephole (-0.0 == 0.0
		// in Java), mirroring JvmQuoteCompiler.emitRawDouble.
		if (value == 0.0 && Double.doubleToRawLongBits(value) == 0L) {
			ctx.body.dconst_0();
		}
		else if (value == 1.0) {
			ctx.body.dconst_1();
		}
		else {
			ConstantPool.DoubleConstant dc = ctx.cp.addDouble(value);
			ctx.body.ldc(dc.entry());
		}
	}

	/**
	 * Pushes a bignum literal. A {@code BigInteger} is immutable, so the value is
	 * interned into a per-compilation pool ({@link JvmLispCompiler.BigIntPool}) and built
	 * once in {@code <clinit>}: the use site is a 3-byte {@code GETSTATIC} rather than an
	 * allocation and a decimal-string parse per execution. In the hottest loops a program
	 * has -- every {@code (ldb (byte 64 0) ...)} masks with a literal past
	 * {@code Long.MAX_VALUE} -- rebuilding it per use dominated the run.
	 * @param value the literal
	 * @param ctx the compilation context
	 */
	static void compileBigInteger(java.math.BigInteger value, JvmLispCompiler.Ctx ctx) {
		ConstantPool.FieldrefConstant ref = ctx.bigIntPool.intern(ctx.cp, ctx.className, value);
		ctx.body.getstatic(ref.entry());
	}

	/**
	 * Compiles a ratio literal to its runtime representation: a normalized
	 * {@code BigInteger[2]} of numerator and denominator.
	 */
	static void compileRatio(am.ik.rontolisp.LispRatio value, JvmLispCompiler.Ctx ctx) {
		ctx.body.iconst_2().anewarray(bigIntegerClass(ctx).entry()).dup().iconst_0();
		compileBigInteger(value.numerator(), ctx);
		ctx.body.aastore().dup().iconst_1();
		compileBigInteger(value.denominator(), ctx);
		ctx.body.aastore();
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
	 * @param notCons where an instance jumps
	 */
	static void emitInstanceExclusion(JvmLispCompiler.Ctx ctx, int tempSlot, MethodCode.Label notCons) {
		if (!ctx.mayUseInstances) {
			return;
		}
		ctx.body.aload(tempSlot).checkcast(ctx.objectArrayClass.entry()).iconst_0().aaload();
		ctx.body.instanceOf(ctx.layoutPool.stringArrayClass(ctx.cp).entry()).ifne(notCons);
	}

	/**
	 * Emits the ASYNC-VALUE exclusion shared by the cons-shaped predicates
	 * ({@code consp}/{@code listp}/{@code atom}): a stream and a stream-read token are
	 * {@code Object[3]}s headed by an interned marker string
	 * ({@link JvmAsyncRuntimeBuilder}), and neither is a cons -- the interpreter and both
	 * WASM backends answer nil for {@code (consp a-stream)}, and this backend answered T
	 * because nothing here excluded them. The marker is compared by IDENTITY, exactly as
	 * {@code _streamp} / {@code _futurep} compare it: both loads come from the same
	 * class's constant pool entry. The value must already be in {@code tempSlot} and
	 * known to be a non-ratio {@code Object[]}. Nothing is emitted when the program
	 * carries no async runtime, so such a program compiles byte-identically to a build
	 * that never knew about them.
	 * @param ctx the compilation context
	 * @param tempSlot the local holding the value
	 * @param notCons where a stream or a stream-read token jumps
	 */
	static void emitAsyncValueExclusion(JvmLispCompiler.Ctx ctx, int tempSlot, MethodCode.Label notCons) {
		if (!ctx.mayUseAsyncValues) {
			return;
		}
		// Length first, like the runtime's own marker test: it rejects every cons
		// (Object[2]) before a string comparison is reached.
		ctx.body.aload(tempSlot).checkcast(ctx.objectArrayClass.entry()).arraylength().iconst_3();
		MethodCode.Label ifNotTriplePos = ctx.body.newLabel();
		ctx.body.if_icmpne(ifNotTriplePos);
		for (String marker : new String[] { JvmAsyncRuntimeBuilder.SMARKER, JvmAsyncRuntimeBuilder.RMARKER }) {
			ctx.body.aload(tempSlot).checkcast(ctx.objectArrayClass.entry()).iconst_0().aaload();
			compileUnspelledLiteral(marker, ctx);
			ctx.body.if_acmpeq(notCons);
		}
		ctx.body.labelBinding(ifNotTriplePos);
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
			ctx.body.ldc(sc.entry());
		}
		else {
			ctx.body.ldc(sc.entry());
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
		ctx.body.istore(tmp).iconst_1().newarray(TypeKind.INT).dup().iconst_0().iload(tmp);
		ctx.body.iastore();
	}

	/**
	 * Unboxes a runtime CHARACTER ({@code int[]}) on top of the operand stack to its
	 * Unicode code point ({@code int}). Casts to {@code [I} first so the JVM verifier
	 * sees an int-array reference, then reads {@code arr[0]}. On entry:
	 * {@code [.., ref]}; on exit: {@code [.., cp:int]}.
	 */
	static void unboxCodePoint(JvmLispCompiler.Ctx ctx) {
		ctx.body.checkcast(charArrayClass(ctx).entry()).iconst_0().iaload();
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

	/** A static-method reference into the class being generated (a runtime helper). */
	static ConstantPool.MethodrefConstant selfMethod(JvmLispCompiler.Ctx ctx, String className, String name,
			String desc) {
		return ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8(name), ctx.cp.addUtf8(desc)));
	}

	/**
	 * Calls a per-class helper holding a sequence that is the SAME wherever it is
	 * emitted, building the method on first use. The arguments are already on the operand
	 * stack, in order; the helper's value replaces them.
	 *
	 * <p>
	 * A type predicate is the shape this is for: {@code atom}, {@code consp} and
	 * {@code stringp} each decide over a dozen host classes and compile to ~90 bytecodes
	 * that depend on nothing but the value, so a generated dispatch that writes
	 * {@code atom} once per clause carried a kilobyte per forty clauses and crossed
	 * HotSpot's {@code HugeMethodLimit} ({@code .kb/hot-path-method-size.md}) --
	 * {@code %error-runtime}'s 57 {@code atom}s alone were 5 KB of its 13.8 KB. As a
	 * static call of four bytes it costs the JIT nothing (the callee is small enough to
	 * inline everywhere) and keeps the caller compilable.
	 * @param ctx the emission context
	 * @param className the class being generated
	 * @param name the helper method's name, which also keys the one-per-class memo
	 * @param arity how many {@code Object} arguments it takes
	 * @param body emits the helper's value over its parameters (slots {@code 0..arity-1})
	 */
	static void emitSharedCall(JvmLispCompiler.Ctx ctx, String className, String name, int arity,
			java.util.function.Consumer<JvmLispCompiler.Ctx> body) {
		ConstantPool.MethodrefConstant ref = ctx.sharedHelpers.get(name);
		if (ref == null) {
			String desc = "(" + "Ljava/lang/Object;".repeat(arity) + ")Ljava/lang/Object;";
			ConstantPool.Utf8Constant nameUtf8 = ctx.cp.addUtf8(name);
			ConstantPool.Utf8Constant descUtf8 = ctx.cp.addUtf8(desc);
			ref = selfMethod(ctx, className, name, desc);
			// Recorded BEFORE the body is emitted so a helper whose own body reaches the
			// same emitter finds it claimed rather than starting a second one.
			ctx.sharedHelpers.put(name, ref);
			JvmLispCompiler.Ctx helper = ctx.ctxBuilder.build();
			helper.evalStoreRef = ctx.evalStoreRef;
			helper.nextLocal = arity;
			helper.maxLocals = arity;
			body.accept(helper);
			helper.body.areturn();
			ctx.outlinedBodies.add(new JvmBodyOutliner.OutlinedBody(name, nameUtf8, descUtf8, helper));
		}
		ctx.body.invokestatic(ref.entry());
	}

	/**
	 * Coerces the {@code Object} on the stack (Long or BigInteger) to a
	 * {@code BigInteger}.
	 */
	static void toBigInteger(JvmLispCompiler.Ctx ctx) {
		ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.BIG_OP).entry());
	}

	/** Normalizes the {@code BigInteger} on the stack to a {@code Long} when it fits. */
	static void normalizeBigInteger(JvmLispCompiler.Ctx ctx) {
		ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.NORM_OP).entry());
	}

	static void unboxLong(JvmLispCompiler.Ctx ctx) {
		ctx.body.checkcast(ctx.longClass.entry()).invokevirtual(ctx.longValue.methodRefEntry());
	}

	static void boxLong(JvmLispCompiler.Ctx ctx) {
		ctx.body.invokestatic(ctx.longValueOf.entry());
	}

	static void unboxDouble(JvmLispCompiler.Ctx ctx) {
		// _dbl coerces Long/BigInteger/Double and ratios (BigInteger[]) to a Double, so
		// float contagion also works when a ratio flows into a double-literal operation.
		ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.DBL).entry());
		ctx.body.checkcast(ctx.numberClass.entry()).invokevirtual(ctx.numberDoubleValue.methodRefEntry());
	}

	static void boxDouble(JvmLispCompiler.Ctx ctx) {
		ctx.body.invokestatic(ctx.doubleValueOf.entry());
	}

	/**
	 * Unboxes a value the emitter routed on the strength of a FLOAT DECLARATION alone:
	 * {@code checkcast Double} + {@code doubleValue}, no {@code _dbl} coercion. A true
	 * declaration makes the cast free (the value IS a Double); a false one fails as a
	 * deterministic {@code ClassCastException} at this site -- the JVM spelling of the
	 * wasm-GC {@code ref.cast} trap, never a silently coerced value
	 * ({@code .kb/declarations-type-checks.md}).
	 */
	static void unboxDeclaredDouble(JvmLispCompiler.Ctx ctx) {
		ctx.body.checkcast(ctx.doubleClass.entry()).invokevirtual(ctx.numberDoubleValue.methodRefEntry());
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
		if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
			ctx.body.loadConstant(value);
		}
		else {
			ctx.body.ldc(ctx.cp.addInteger(value).entry());
		}
	}

	/**
	 * Converts an i32 (0=false, non-0=true) on the JVM stack into a Lisp boolean
	 * (null=nil or the symbol {@code t}).
	 */
	static void emitBoolFromInt(JvmLispCompiler.Ctx ctx) {
		MethodCode.Label ifPos = ctx.body.newLabel();
		ctx.body.ifne(ifPos);
		ctx.body.aconst_null();
		MethodCode.Label gotoEndPos = ctx.body.newLabel();
		ctx.body.goto_(gotoEndPos);
		ctx.body.labelBinding(ifPos);
		compileTrue(ctx);
		ctx.body.labelBinding(gotoEndPos);
	}

	/**
	 * A branch whose opcode is chosen at run time, named by its byte ({@link Opcode}):
	 * the typed layer's short branch of that kind.
	 * @param ctx the compile context
	 * @param opcode a conditional branch or {@code goto}
	 * @param target where it jumps
	 */
	static void branch(JvmLispCompiler.Ctx ctx, int opcode, MethodCode.Label target) {
		for (java.lang.classfile.Opcode op : java.lang.classfile.Opcode.values()) {
			if (op.bytecode() == opcode && op.kind() == java.lang.classfile.Opcode.Kind.BRANCH
					&& op.sizeIfFixed() == 3) {
				ctx.body.branch(op, target);
				return;
			}
		}
		throw new IllegalArgumentException("not a short branch: " + opcode);
	}

	/**
	 * Checks the value on the stack is a list, leaving it there: nil or a cons passes,
	 * anything else is the innermost named operator's {@code LIST} type-error
	 * ({@code _ckList} through the operator's wrapper, {@link JvmOperandTypeRuntime}).
	 * @param ctx the compile context
	 */
	static void emitListCheck(JvmLispCompiler.Ctx ctx) {
		ctx.body.invokestatic(ctx.numOp(JvmOperandTypeRuntime.CK_LIST).entry());
	}

	/**
	 * Boxes a local variable in an Object[1] cell for capture-by-reference.
	 */
	static void emitBoxLocal(JvmLispCompiler.Ctx ctx, int slot) {
		ctx.body.iconst_1().anewarray(ctx.objectClass.entry()).dup().iconst_0().aload(slot);
		ctx.body.aastore().astore(slot);
	}

}
