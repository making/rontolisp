package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.wasm.WasmFdlibmRuntimeBuilder.Fn;
import am.ik.wasm.Instruction;

/**
 * Compiles the real transcendental built-ins ({@code exp}, {@code log}, {@code sin},
 * {@code cos}, {@code tan}, {@code asin}, {@code acos}, {@code atan}, {@code sinh},
 * {@code cosh}, {@code tanh}) as calls into the fdlibm runtime
 * ({@link WasmFdlibmRuntimeBuilder}), and lends the complex compiler the same calls over
 * boxed temporaries. WASM has no transcendental instruction; every one of these used to
 * be a software approximation emitted inline at each site, close to but not the JVM's
 * bits. They are now the JVM's bits ({@code .kb/transcendentals.md}): one function per
 * algorithm, the argument coerced through the shared {@code _as_f64}, the result boxed.
 */
final class WasmTranscendentalCompiler {

	private WasmTranscendentalCompiler() {
	}

	/**
	 * Emits {@code call} to the fdlibm function, recording it as reached so its slot gets
	 * a real body ({@code Ctx.fdlibm}).
	 * @param ctx the compile context
	 * @param fn the function
	 */
	static void call(WasmLispCompiler.Ctx ctx, Fn fn) {
		WasmOperandTypes.emitCall(ctx, ctx.fdlibm(fn));
	}

	/**
	 * {@code (name x)} over a real: the argument as f64, the call, the boxed result.
	 * @param cons the call form
	 * @param ctx the compile context
	 * @param fn the fdlibm function the name maps to
	 * @param name the Lisp name, for the arity message
	 */
	static void compileUnary(LispCons cons, WasmLispCompiler.Ctx ctx, Fn fn, String name) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException(name + " expects 1 argument, got " + (args.size() - 1));
		}
		WasmComplexBlock complexBlock = ctx.complexBlock;
		WasmComplexBlock.Fn entry = complexBlock == null ? null : WasmComplexBlock.unary(name);
		if (complexBlock != null && entry != null && WasmFloatOperands.guards(args.subList(1, 2), ctx)) {
			compileHolderAware(args.get(1), ctx, fn, complexBlock, entry);
			return;
		}
		compileArg(args.get(1), ctx, fn);
	}

	/**
	 * A program that may observe a complex, and an argument a variable or a call
	 * produces: a {@code TYPE_COMPLEX} goes to the complex block's formula, every real to
	 * the call it always took -- one test in front of the {@code _as_f64} that would
	 * reject a complex (`.kb/wasm-complex.md`, "A complex through a variable").
	 */
	private static void compileHolderAware(LispVal arg, WasmLispCompiler.Ctx ctx, Fn fn, WasmComplexBlock complexBlock,
			WasmComplexBlock.Fn entry) {
		WasmExprCompiler.compileExpr(arg, ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, am.ik.wasm.Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		complexBlock.emitCall(ctx, entry);
		ctx.writer.write(Instruction.ELSE);
		// A float argument is read straight out of its box -- _as_f64's first rung,
		// without the call -- which pays for the test in front of it.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(am.ik.wasm.Type.F64);
		WasmEmitHelper.unboxF64Local(ctx, slot);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.END);
		call(ctx, fn);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * {@code fn} of one argument FORM, leaving the boxed float -- the shape the
	 * two-argument {@code (log n base)} needs, which takes two logarithms out of one call
	 * form.
	 * @param arg the argument form
	 * @param ctx the compile context
	 * @param fn the function
	 */
	static void compileArg(LispVal arg, WasmLispCompiler.Ctx ctx, Fn fn) {
		WasmExprCompiler.compileExpr(arg, ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		call(ctx, fn);
		WasmEmitHelper.boxF64(ctx);
	}

	/**
	 * {@code fn} of the boxed float in {@code inBox}, boxed into {@code outBox}.
	 * @param ctx the compile context
	 * @param fn a unary function
	 * @param inBox the boxed argument
	 * @param outBox the boxed result slot
	 */
	static void callInto(WasmLispCompiler.Ctx ctx, Fn fn, int inBox, int outBox) {
		WasmEmitHelper.unboxF64Local(ctx, inBox);
		call(ctx, fn);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(outBox);
	}

	/**
	 * {@code fn} of the two boxed floats, boxed into {@code outBox}.
	 * @param ctx the compile context
	 * @param fn a binary function
	 * @param aBox the boxed first argument
	 * @param bBox the boxed second argument
	 * @param outBox the boxed result slot
	 */
	static void callInto(WasmLispCompiler.Ctx ctx, Fn fn, int aBox, int bBox, int outBox) {
		WasmEmitHelper.unboxF64Local(ctx, aBox);
		WasmEmitHelper.unboxF64Local(ctx, bBox);
		call(ctx, fn);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(outBox);
	}

}
