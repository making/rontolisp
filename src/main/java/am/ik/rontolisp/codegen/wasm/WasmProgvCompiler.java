package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the four internal operators of the {@code progv} lowering
 * ({@code LispMacroExpander.expandProgvForCompile}) -- the WASM twin of
 * {@code JvmProgvCompiler}. Each arm of the lowering's name-dispatch chain names its
 * special LITERALLY, so these emit exactly the save/set the {@code let} path emits for
 * that one special ({@code WasmLetCompiler}): shallow save/set over the module global, or
 * over the per-task record's slot under {@code --reentrant} ({@code WasmDynVars}). The
 * previous binding state flows as a VALUE (consed into the lowering's save list) instead
 * of into a save local, because the bind and its restore sit in different loop iterations
 * of the same {@code unwind-protect}.
 *
 * <p>
 * {@code %progv-genv}/{@code %progv-genv-set} read/write {@code GLOBAL_ENV}, the eval
 * runtime's global env mirror, whose bindings are ordinary cons cells -- so the lowering
 * maintains the mirror in plain Lisp. Only emitted when the eval runtime exists
 * ({@code Ctx.usesEval}).
 */
final class WasmProgvCompiler {

	private WasmProgvCompiler() {
	}

	/**
	 * {@code (%progv-dyn-bind NAME value)}: push a binding, answer the previous state.
	 */
	static void compileDynBind(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		String name = ((LispSymbol) parts.get(1)).name();
		WasmExprCompiler.compileExpr(parts.get(2), ctx);
		int tmp = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmp);
		if (WasmDynVars.handles(ctx, name)) {
			WasmDynVars.emitProgvBind(ctx, name, tmp);
			return;
		}
		if (ctx.reentrant) {
			// The WasmLetCompiler rule: a special being bound that the per-task store
			// does not carry must fail the compile, never fall back to a binding another
			// task can read back.
			throw new IllegalStateException("special variable " + name + " has no per-task slot for the progv lowering"
					+ " (SpecialVarCollector.collectDynamicallyBound missed the progv)");
		}
		Integer globalIndex = ctx.globalIndices.get(name);
		if (globalIndex == null) {
			throw new IllegalStateException(
					"special variable " + name + " has no module global for the progv lowering");
		}
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(globalIndex);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmp);
		ctx.writer.write(Instruction.SET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(globalIndex);
	}

	/** {@code (%progv-dyn-unbind NAME prev)}: restore the saved state; answers nil. */
	static void compileDynUnbind(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		String name = ((LispSymbol) parts.get(1)).name();
		WasmExprCompiler.compileExpr(parts.get(2), ctx);
		if (WasmDynVars.handles(ctx, name)) {
			int tmp = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmp);
			WasmDynVars.emitProgvUnbind(ctx, name, tmp);
		}
		else {
			Integer globalIndex = ctx.globalIndices.get(name);
			if (globalIndex == null) {
				throw new IllegalStateException(
						"special variable " + name + " has no module global for the progv lowering");
			}
			ctx.writer.write(Instruction.SET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(globalIndex);
		}
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

	/** {@code (%progv-genv)}: the eval runtime's global env mirror, as a Lisp alist. */
	static void compileGenvRead(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_ENV);
	}

	/** {@code (%progv-genv-set x)}: replace the mirror alist; answers nil. */
	static void compileGenvWrite(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		ctx.writer.write(Instruction.SET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_ENV);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}
