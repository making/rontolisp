package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * The argument sequence a compiled callee takes, emitted where a call's argument count is
 * known: its required parameters, then its PHYSICAL optionals -- each an argument or the
 * UNSUPPLIED marker -- then, for a variadic callee, the rest list of whatever is past
 * them (nil when nothing is). An optional argument therefore travels as a parameter and
 * conses nothing; only a surplus past the last physical optional is linked into a list
 * ({@code LambdaLists.toNative}). The dispatchers ({@code WasmRuntimeBuilder}) build the
 * same sequence out of their own parameters and argument lists.
 *
 * <p>
 * The marker is the module's raw-local sentinel ({@code Ctx.rawSentinelGlobalIndex}): an
 * immutable global every module declares, initialized by a constant
 * {@code struct.new TYPE_CELL} no Lisp value can be {@code ref.eq} to. nil cannot mark an
 * argument that was not passed -- nil is {@code ref.null}, a value a caller passes like
 * any other. Only a {@code %supplied-p} prologue reads a physical optional, so the marker
 * never reaches a Lisp binding.
 */
final class WasmPhysicalArgs {

	private WasmPhysicalArgs() {
	}

	/**
	 * Pushes the UNSUPPLIED marker.
	 * @param ctx the function context
	 */
	static void emitUnsupplied(WasmLispCompiler.Ctx ctx) {
		emitUnsupplied(ctx.writer, ctx.rawSentinelGlobalIndex);
	}

	/**
	 * Pushes the UNSUPPLIED marker.
	 * @param w the writer
	 * @param markerGlobal the module global holding it
	 */
	static void emitUnsupplied(am.ik.wasm.WasmWriter w, int markerGlobal) {
		if (markerGlobal < 0) {
			throw new IllegalStateException(
					"a callee with physical optionals was dispatched without the" + " UNSUPPLIED marker's global");
		}
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(markerGlobal);
	}

	/**
	 * Emits the physical arguments of a call to {@code fi}, after the environment the
	 * caller pushed. Each element of {@code args} pushes one argument and is run exactly
	 * once, left to right.
	 * @param ctx the function context
	 * @param fi the callee
	 * @param args one emitter per argument; a count the callee accepts
	 */
	static void emit(WasmLispCompiler.Ctx ctx, WasmLispCompiler.WasmFunctionInfo fi, List<Runnable> args) {
		emit(ctx, fi.required(), fi.optionals(), fi.variadic(), args);
	}

	/**
	 * Emits the physical arguments of a call to a callee of the given shape. Each element
	 * of {@code args} pushes one argument and is run exactly once, left to right: the
	 * ones a parameter takes straight onto the operand stack, a surplus into temps first
	 * and then linked into the rest list, newest link first.
	 * @param ctx the function context
	 * @param required the callee's required parameter count
	 * @param optionals its physical optional count
	 * @param variadic whether it takes a rest list
	 * @param args one emitter per argument; a count the callee accepts
	 */
	static void emit(WasmLispCompiler.Ctx ctx, int required, int optionals, boolean variadic, List<Runnable> args) {
		int positional = required + optionals;
		int supplied = args.size();
		if (!variadic || supplied <= positional) {
			args.forEach(Runnable::run);
			for (int i = supplied; i < positional; i++) {
				emitUnsupplied(ctx);
			}
			if (variadic) {
				emitNull(ctx);
			}
			return;
		}
		for (int i = 0; i < positional; i++) {
			args.get(i).run();
		}
		List<Integer> extraSlots = new ArrayList<>();
		for (int i = positional; i < supplied; i++) {
			args.get(i).run();
			int slot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
			extraSlots.add(slot);
		}
		int restSlot = ctx.allocTemp();
		emitNull(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(restSlot);
		for (int k = extraSlots.size() - 1; k >= 0; k--) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(extraSlots.get(k));
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(restSlot);
			WasmEmitHelper.emitNewCons(ctx);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(restSlot);
		}
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(restSlot);
	}

	/**
	 * Emits the physical arguments of a call to {@code fi} read out of the argument LIST
	 * in {@code listSlot}, after the environment the caller pushed: each required
	 * parameter its element, each physical optional its element or -- past the end of the
	 * list -- the marker, and the rest list the tail past them. Each element is re-walked
	 * from the head. The count has been judged already ({@code _arity_chk}); a required
	 * parameter past the end binds nil, like the {@code car}/{@code cdr} it is.
	 * @param ctx the function context
	 * @param fi the callee
	 * @param listSlot the local holding the argument list
	 */
	static void emitFromList(WasmLispCompiler.Ctx ctx, WasmLispCompiler.WasmFunctionInfo fi, int listSlot) {
		int positional = fi.positional();
		for (int i = 0; i < positional; i++) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(listSlot);
			for (int step = 0; step < i; step++) {
				WasmEmitHelper.emitConsField(ctx, 1);
			}
			if (i < fi.required()) {
				WasmEmitHelper.emitConsField(ctx, 0);
				continue;
			}
			// cell == nil ? UNSUPPLIED : car(cell)
			int cellSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.TEE_LOCAL);
			ctx.writer.writeUnsignedLeb128(cellSlot);
			ctx.writer.write(Instruction.REF_IS_NULL);
			ctx.writer.write(Instruction.IF);
			ctx.writer.writeRefType(true, Type.EQ.code());
			ctx.wasmCtrlDepth++;
			emitUnsupplied(ctx);
			ctx.writer.write(Instruction.ELSE);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(cellSlot);
			WasmEmitHelper.emitConsField(ctx, 0);
			ctx.wasmCtrlDepth--;
			ctx.writer.write(Instruction.END);
		}
		if (fi.variadic()) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(listSlot);
			for (int step = 0; step < positional; step++) {
				WasmEmitHelper.emitConsField(ctx, 1);
			}
		}
	}

	/**
	 * {@code (%supplied-p param)} as a VALUE -- the supplied-p variable a prologue binds:
	 * nil for the UNSUPPLIED marker, else {@code t}. The {@code t} is read out of its
	 * cache global in place, {@code _t_sym} called only while the cache is still empty:
	 * the supplied arm is the one every call that passes the argument takes, and a call
	 * there cost a wrapper like {@code #'<} a fifth of a two-argument call on wasmtime
	 * (2026-09-26). A test of it never gets here ({@code WasmConditionCompiler}).
	 * @param cons the {@code %supplied-p} form
	 * @param ctx the function context
	 */
	static void compileSuppliedP(am.ik.rontolisp.LispCons cons, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(cons.toList().get(1), ctx);
		emitUnsupplied(ctx);
		ctx.writer.write(Instruction.REF_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		emitNull(ctx);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(ctx.tSymGlobalIndex());
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		WasmEmitHelper.emitTrue(ctx);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(ctx.tSymGlobalIndex());
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	/** {@code ref.null eq} -- the empty rest list. */
	private static void emitNull(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}
