package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import am.ik.rontolisp.LispNames;
import am.ik.wasm.Instruction;

/**
 * The complex block: the functions an operator's call site hands a {@code TYPE_COMPLEX}
 * to when it arrived through a VARIABLE -- a parameter, a global, a list element --
 * rather than through a complex literal or a {@code complex} form the site steers on at
 * compile time. Present only in a module whose program may observe a complex
 * ({@link am.ik.rontolisp.compiler.ComplexCapability}), right in front of the user
 * functions, so it moves no fixed index and every other module is byte-identical
 * (`.kb/wasm-complex.md`, "A complex through a variable").
 *
 * <p>
 * Each body is the complex formula a literal-steered site emits inline, emitted once over
 * the function's parameters by the same emitter ({@link WasmComplexCompiler}). The site
 * keeps its real path inline and tests its operand for a complex in front of it: a call
 * for the complex, nothing new for a real. Built before any user body compiles, so a call
 * site can record the fdlibm functions the callee reaches into its own body's set -- the
 * fdlibm runtime gives a real body exactly to what a REACHABLE body records.
 *
 * <p>
 * {@code + - * /} need no entry: their shared {@code _rat_*} helpers carry the arm
 * themselves ({@link WasmRatioRuntimeBuilder}). An ordering needs none either: a complex
 * already lands in {@code _rat_cmp_bits}'s REAL report there, which is why {@code =} has
 * an entry of its own instead of an arm in that shared function.
 */
final class WasmComplexBlock {

	/** The block's functions, in index order from its base. */
	enum Fn {

		/**
		 * {@code =} over two numbers, a stand-in for {@code _rat_cmp_bits} at an
		 * {@code =} site: part-wise for a complex operand, that function's eq bit
		 * otherwise; answers 2 for equal and 0 for not.
		 */
		NUM_EQ(WasmLispCompiler.TYPE_RAT_CMP, 2, LispNames.EQ),

		/** {@code abs} of a complex: the float modulus. */
		ABS(WasmLispCompiler.TYPE_CALLABLE_BASE, 1, LispNames.ABS),

		/** {@code exp} of a complex. */
		EXP(WasmLispCompiler.TYPE_CALLABLE_BASE, 1, LispNames.EXP),

		/** {@code sin} of a complex. */
		SIN(WasmLispCompiler.TYPE_CALLABLE_BASE, 1, LispNames.SIN),

		/** {@code cos} of a complex. */
		COS(WasmLispCompiler.TYPE_CALLABLE_BASE, 1, LispNames.COS),

		/** {@code tan} of a complex. */
		TAN(WasmLispCompiler.TYPE_CALLABLE_BASE, 1, LispNames.TAN),

		/** {@code atan} of a complex. */
		ATAN(WasmLispCompiler.TYPE_CALLABLE_BASE, 1, LispNames.ATAN),

		/** {@code sinh} of a complex. */
		SINH(WasmLispCompiler.TYPE_CALLABLE_BASE, 1, LispNames.SINH),

		/** {@code cosh} of a complex. */
		COSH(WasmLispCompiler.TYPE_CALLABLE_BASE, 1, LispNames.COSH),

		/** {@code tanh} of a complex. */
		TANH(WasmLispCompiler.TYPE_CALLABLE_BASE, 1, LispNames.TANH),

		/** {@code expt} with a complex base or power. */
		EXPT(WasmLispCompiler.TYPE_CALLABLE_BASE + 1, 2, LispNames.EXPT);

		/** The function's type index (a fixed type every module already has). */
		final int typeIndex;

		final int params;

		final String lispName;

		Fn(int typeIndex, int params, String lispName) {
			this.typeIndex = typeIndex;
			this.params = params;
			this.lispName = lispName;
		}

	}

	/** How many functions the block holds. */
	static final int FUNC_COUNT = Fn.values().length;

	private final int funcBase;

	private final Map<Fn, byte[]> bodies = new EnumMap<>(Fn.class);

	private final Map<Fn, Set<WasmFdlibmRuntimeBuilder.Fn>> fdlibmUses = new EnumMap<>(Fn.class);

	/**
	 * Builds every body of the block.
	 * @param funcBase the module index of the first function
	 * @param contexts a fresh emission context over a body stream, recording its fdlibm
	 * calls into the given set
	 */
	WasmComplexBlock(int funcBase,
			BiFunction<ByteArrayOutputStream, Set<WasmFdlibmRuntimeBuilder.Fn>, WasmLispCompiler.Ctx> contexts) {
		this.funcBase = funcBase;
		for (Fn fn : Fn.values()) {
			ByteArrayOutputStream stream = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
			Set<WasmFdlibmRuntimeBuilder.Fn> uses = EnumSet.noneOf(WasmFdlibmRuntimeBuilder.Fn.class);
			WasmLispCompiler.Ctx ctx = contexts.apply(stream, uses);
			ctx.nextLocal = fn.params;
			switch (fn) {
				case NUM_EQ -> {
					emitSameTierEq(ctx);
					WasmComplexCompiler.emitEqPair(ctx, 0, 1);
				}
				case ABS -> WasmComplexCompiler.emitAbsOf(ctx, 0);
				case EXPT -> WasmComplexCompiler.emitExptOf(ctx, 0, 1);
				default -> WasmComplexCompiler.emitUnaryMathOf(ctx, 0, fn.lispName);
			}
			ctx.writer.write(Instruction.END);
			this.bodies.put(fn, WasmLispCompiler.buildLocalsAndPatch(ctx, fn.params, stream));
			this.fdlibmUses.put(fn, uses);
		}
	}

	/**
	 * The head of the {@code =} entry: two i31 integers, and two floats, answer here --
	 * what {@code _rat_cmp_bits}'s eq bit would for them, {@code ref.eq} on an i31 being
	 * value equality and {@code f64.eq} what that function's float pair decides -- so the
	 * pairs a real program compares most never meet the complex test, nor the call the
	 * site used to make.
	 */
	private static void emitSameTierEq(WasmLispCompiler.Ctx ctx) {
		for (int heapType : new int[] { am.ik.wasm.Type.I31.code(), WasmLispCompiler.TYPE_FLOAT }) {
			for (int slot = 0; slot < 2; slot++) {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
				ctx.writer.writeHeapType(heapType);
			}
			ctx.writer.write(Instruction.I32_AND);
			ctx.writer.write(Instruction.IF, 0x40);
			for (int slot = 0; slot < 2; slot++) {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slot);
				if (heapType == WasmLispCompiler.TYPE_FLOAT) {
					ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
					ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
					ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
					ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
					ctx.writer.writeUnsignedLeb128(0);
				}
			}
			ctx.writer.write(heapType == WasmLispCompiler.TYPE_FLOAT ? Instruction.F64_EQ : Instruction.REF_EQ);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(1);
			ctx.writer.write(Instruction.I32_SHL);
			ctx.writer.write(Instruction.RETURN);
			ctx.writer.write(Instruction.END);
		}
	}

	/**
	 * The module index of a block function.
	 * @param fn the function
	 * @return its index
	 */
	int funcIndex(Fn fn) {
		return this.funcBase + fn.ordinal();
	}

	/**
	 * Emits a call to a block function from a site, recording the fdlibm functions its
	 * body calls as the site's own: they get real bodies exactly when the site is
	 * reachable.
	 * @param ctx the site's context
	 * @param fn the function
	 */
	void emitCall(WasmLispCompiler.Ctx ctx, Fn fn) {
		ctx.fdlibmUsed.addAll(java.util.Objects.requireNonNull(this.fdlibmUses.get(fn)));
		WasmOperandTypes.emitCall(ctx, funcIndex(fn));
	}

	/**
	 * A block function's body, locals declaration included.
	 * @param fn the function
	 * @return the body
	 */
	byte[] body(Fn fn) {
		return java.util.Objects.requireNonNull(this.bodies.get(fn));
	}

	/**
	 * The block's unary entry for one of the float unary functions, or null for a name
	 * with none.
	 * @param name the (uppercase-canonical) Lisp name
	 * @return the entry, or null
	 */
	static @org.jspecify.annotations.Nullable Fn unary(String name) {
		return switch (name) {
			case LispNames.EXP -> Fn.EXP;
			case LispNames.SIN -> Fn.SIN;
			case LispNames.COS -> Fn.COS;
			case LispNames.TAN -> Fn.TAN;
			case LispNames.ATAN -> Fn.ATAN;
			case LispNames.SINH -> Fn.SINH;
			case LispNames.COSH -> Fn.COSH;
			case LispNames.TANH -> Fn.TANH;
			default -> null;
		};
	}

}
