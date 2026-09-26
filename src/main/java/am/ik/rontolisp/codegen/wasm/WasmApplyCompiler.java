package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code apply} built-in function. The leading arguments are taken literally
 * and the final argument is a list whose elements are spread; the full argument list is
 * built as {@code (cons arg1 (cons ... lastList))} and passed to the runtime
 * {@code _apply} helper. Using {@code apply} forces the eval runtime to be emitted (see
 * {@code WasmLispCompiler}), which provides {@code _apply}.
 */
final class WasmApplyCompiler {

	private WasmApplyCompiler() {
	}

	// Replaces the cons on the stack with its car (field 0) or cdr (field 1); nil
	// passes through, like the car/cdr built-ins (the same shared shape).
	private static void emitNullSafeCell(WasmLispCompiler.Ctx ctx, int field) {
		WasmEmitHelper.emitConsField(ctx, field);
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, false);
	}

	/**
	 * As {@link #compile(LispCons, WasmLispCompiler.Ctx)}; with {@code tail}, the final
	 * call -- the physical direct call or {@code _apply} -- is a {@code return_call}
	 * ({@code Ctx.tailPosition}).
	 */
	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, boolean tail) {
		List<LispVal> args = cons.toList();
		int n = args.size();

		// A literal #'f/'f designator naming a compiled function compiles to a
		// PHYSICAL direct call: the runtime argument list is built once, the required
		// parameters are car/cdr-walked out of it and a variadic target's trailing
		// rest parameter takes the remaining tail verbatim (a plain direct call would
		// re-bundle it). This bypasses _apply, whose per-arity dispatch stops at
		// MAX_CALLABLE_ARITY (a variadic CLOS dispatcher forwarding 8+ apply
		// arguments silently yielded nil there).
		String target = n >= 3 ? am.ik.rontolisp.macro.LispMacroExpander.applyLiteralTargetName(args.get(1)) : null;
		if (target != null) {
			WasmLispCompiler.WasmFunctionInfo fi = ctx.functions.get(target);
			if (fi != null && fi.variadic() && n - 3 >= fi.paramCount() - 1) {
				// Aligned: the leading arguments cover every required parameter, so
				// the argument list needs no build-then-unpack round trip -- required
				// parameters are the leading expressions and the rest parameter takes
				// the tail verbatim (or the excess consed onto it), in source order.
				int required = fi.paramCount() - 1;
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
				for (int i = 0; i < required; i++) {
					WasmExprCompiler.compileExpr(args.get(2 + i), ctx);
				}
				WasmExprCompiler
					.compileExpr(am.ik.rontolisp.macro.LispMacroExpander.applyAlignedRestExpr(cons, required), ctx);
				ctx.writer.write(WasmUncaughtLocations.tailCallOp(ctx, tail, target));
				ctx.writer.writeUnsignedLeb128(fi.funcIndex());
				return;
			}
			if (fi != null) {
				WasmExprCompiler.compileExpr(am.ik.rontolisp.macro.LispMacroExpander.applyArgumentListExpr(cons), ctx);
				int argsSlot = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(argsSlot);
				int required = fi.variadic() ? fi.paramCount() - 1 : fi.paramCount();
				// The count guard. This call reaches no dispatcher, so no no-match arm
				// can report a wrong count for it, and the walk below is car/cdr -- a
				// short list would BIND nil for the parameters it does not reach and a
				// long one would drop its tail. _arity_chk measures the list against the
				// shape baked here and throws ClosRegistry.arityMessage's text, the same
				// function a SPREAD dispatcher case calls.
				if (ctx.arityChkFuncIndex >= 0) {
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writer.writeUnsignedLeb128(argsSlot);
					ctx.writer.write(Instruction.I32_CONST);
					// A built-in callee's shape carries its funcId, so the report names
					// the
					// operator (WasmRuntimeBuilder.ArityReport).
					boolean named = ctx.namesArityOperators
							&& am.ik.rontolisp.compiler.BuiltinFunctionWrappers.arityOperator(target) != null
							&& fi.funcId() < WasmRuntimeBuilder.ARITY_MAX_NAMED_FUNC_ID;
					if (named) {
						ctx.arityNamedCallees.add(fi.funcId());
					}
					ctx.writer.writeSignedLeb128(
							WasmRuntimeBuilder.arityShape(required, fi.variadic(), named ? fi.funcId() : -1));
					ctx.writer.write(Instruction.CALL);
					ctx.writer.writeUnsignedLeb128(ctx.arityChkFuncIndex);
					ctx.writer.write(Instruction.DROP);
				}
				// Push null env first (defun functions ignore it), like the direct-call
				// convention.
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
				for (int i = 0; i < required; i++) {
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writer.writeUnsignedLeb128(argsSlot);
					for (int step = 0; step < i; step++) {
						emitNullSafeCell(ctx, 1);
					}
					emitNullSafeCell(ctx, 0);
				}
				if (fi.variadic()) {
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writer.writeUnsignedLeb128(argsSlot);
					for (int step = 0; step < required; step++) {
						emitNullSafeCell(ctx, 1);
					}
				}
				ctx.writer.write(WasmUncaughtLocations.tailCallOp(ctx, tail, target));
				ctx.writer.writeUnsignedLeb128(fi.funcIndex());
				return;
			}
		}

		// Compile the function designator.
		WasmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx);
		int funcSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(funcSlot);

		// Compile the leading literal arguments (indices 2 .. n-2), left to right.
		List<Integer> argSlots = new ArrayList<>();
		for (int i = 2; i < n - 1; i++) {
			WasmExprCompiler.compileExpr(args.get(i), ctx);
			int s = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(s);
			argSlots.add(s);
		}

		// Compile the final list argument; it becomes the tail of the argument list.
		WasmExprCompiler.compileExpr(args.get(n - 1), ctx);
		int curSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(curSlot);

		// Prepend each leading argument: cur = cons(arg, cur).
		for (int k = argSlots.size() - 1; k >= 0; k--) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(argSlots.get(k));
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(curSlot);
			WasmEmitHelper.emitNewCons(ctx);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(curSlot);
		}

		// _apply(func, argList)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(funcSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(curSlot);
		WasmUncaughtLocations.emitValueCall(ctx, tail, 2, WasmLispCompiler.FUNC_APPLY);
	}

}
