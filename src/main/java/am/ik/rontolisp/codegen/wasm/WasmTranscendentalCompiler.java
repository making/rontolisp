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
		compileArg(args.get(1), ctx, fn);
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
