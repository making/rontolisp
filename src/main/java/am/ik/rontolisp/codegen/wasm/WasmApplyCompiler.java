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
	// passes through, like the car/cdr built-ins.
	private static void emitNullSafeCell(WasmLispCompiler.Ctx ctx, int field) {
		int tmpSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(field);
		ctx.writer.write(Instruction.END);
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int n = args.size();

		// A literal #'f/'f designator naming a compiled function compiles to a
		// PHYSICAL direct call: the runtime argument list is built once, the required
		// parameters are car/cdr-walked out of it and a variadic target's trailing
		// rest parameter takes the remaining tail verbatim (a plain direct call would
		// re-bundle it). This bypasses _apply, whose per-arity dispatch stops at
		// MAX_CALLABLE_ARITY (a variadic CLOS dispatcher forwarding 8+ apply
		// arguments silently yielded nil there).
		String target = n >= 3 ? am.ik.rontolisp.LispMacroExpander.applyLiteralTargetName(args.get(1)) : null;
		if (target != null) {
			WasmLispCompiler.WasmFunctionInfo fi = ctx.functions.get(target);
			if (fi != null) {
				WasmExprCompiler.compileExpr(am.ik.rontolisp.LispMacroExpander.applyArgumentListExpr(cons), ctx);
				int argsSlot = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(argsSlot);
				// Push null env first (defun functions ignore it), like the direct-call
				// convention.
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
				int required = fi.variadic() ? fi.paramCount() - 1 : fi.paramCount();
				for (int i = 0; i < required; i++) {
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writer.writeSignedLeb128(argsSlot);
					for (int step = 0; step < i; step++) {
						emitNullSafeCell(ctx, 1);
					}
					emitNullSafeCell(ctx, 0);
				}
				if (fi.variadic()) {
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writer.writeSignedLeb128(argsSlot);
					for (int step = 0; step < required; step++) {
						emitNullSafeCell(ctx, 1);
					}
				}
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(fi.funcIndex());
				return;
			}
		}

		// Compile the function designator.
		WasmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx);
		int funcSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(funcSlot);

		// Compile the leading literal arguments (indices 2 .. n-2), left to right.
		List<Integer> argSlots = new ArrayList<>();
		for (int i = 2; i < n - 1; i++) {
			WasmExprCompiler.compileExpr(args.get(i), ctx);
			int s = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(s);
			argSlots.add(s);
		}

		// Compile the final list argument; it becomes the tail of the argument list.
		WasmExprCompiler.compileExpr(args.get(n - 1), ctx);
		int curSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(curSlot);

		// Prepend each leading argument: cur = cons(arg, cur).
		for (int k = argSlots.size() - 1; k >= 0; k--) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(argSlots.get(k));
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(curSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(curSlot);
		}

		// _apply(func, argList)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(funcSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(curSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_APPLY);
	}

}
