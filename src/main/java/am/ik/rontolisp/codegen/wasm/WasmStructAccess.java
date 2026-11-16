package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import org.jspecify.annotations.Nullable;

/**
 * A call of a {@code defstruct} predicate or slot reader as the primitive its body is:
 * {@code (point-p x)} is {@code (%obj-is x 'tag)} and {@code (point-x p)} the checked
 * {@code (%obj-ref p 0 failure)} ({@code LispMacroExpander.expandDefstruct}), each one
 * type test (and a field read) where the call was a frame around it. At the speed levels,
 * for a name with one definition that nothing can replace while the program runs: no
 * {@code --dynamic}, no cell ({@link WasmFunctionCells}), no forwarder.
 *
 * <p>
 * A reader handed anything but an instance calls the definition, which checks and reports
 * as it always did; the definition also stays for {@code #'name}, and the tree-shaker
 * drops it when nothing reaches it.
 */
final class WasmStructAccess {

	private WasmStructAccess() {
	}

	/**
	 * Whether {@code defun} is a one-parameter function whose whole body is a type test
	 * of the parameter against quoted tags, or a read of one of its slots by a literal
	 * index -- with the failure operand of a checked read, a call over the parameter and
	 * constants. The shapes {@code defstruct} generates; any other definition of the same
	 * shape reads the same.
	 * @param defun the defun
	 * @return whether its calls may be the primitive
	 */
	static boolean isAccessDefun(WasmLispCompiler.DefunDecl defun) {
		if (defun.variadic() || defun.optionals() != 0 || defun.paramNames().size() != 1) {
			return false;
		}
		String param = defun.paramNames().get(0);
		List<LispVal> parts = primitive(defun);
		if (parts == null || parts.size() < 2 || !(parts.get(0) instanceof LispSymbol head)
				|| !(parts.get(1) instanceof LispSymbol object) || !object.name().equals(param)) {
			return false;
		}
		if (LispNames.OBJ_IS.equals(head.name())) {
			return parts.subList(2, parts.size()).stream().allMatch(WasmStructAccess::isQuoted);
		}
		if (!LispNames.OBJ_REF.equals(head.name()) || parts.size() < 3 || parts.size() > 4
				|| !(parts.get(2) instanceof LispInteger)) {
			return false;
		}
		return parts.size() == 3 || parts.get(3) instanceof LispCons failure && failure.isProperList()
				&& failure.car() instanceof LispSymbol
				&& failure.toList()
					.stream()
					.skip(1)
					.allMatch(operand -> isConstant(operand)
							|| operand instanceof LispSymbol symbol && symbol.name().equals(param));
	}

	// The body's one expression, read through the clear its settled tail may carry.
	private static @Nullable List<LispVal> primitive(WasmLispCompiler.DefunDecl defun) {
		return WasmIntFusionCompiler.inlinableBody(defun) instanceof LispCons cons && cons.isProperList()
				? cons.toList() : null;
	}

	private static boolean isQuoted(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head && LispNames.isQuote(head.name());
	}

	private static boolean isConstant(LispVal form) {
		return form instanceof LispNil || form instanceof LispTrue || form instanceof LispString
				|| form instanceof LispInteger || form instanceof LispSymbol symbol && symbol.isKeyword()
				|| isQuoted(form);
	}

	/**
	 * Compiles a call of a predicate or a reader as its primitive over the argument,
	 * leaving the value; or emits nothing for a call that stays one. The argument is
	 * evaluated once, then the multiple-value channel is cleared where the definition's
	 * settled tail clears it ({@code .kb/multiple-values.md}), as the call would have.
	 * @param call the call form
	 * @param ctx the compile context
	 * @return whether the call was compiled
	 */
	static boolean tryCompile(LispCons call, WasmLispCompiler.Ctx ctx) {
		WasmLispCompiler.DefunDecl defun = accessDefun(call, ctx);
		if (defun == null) {
			return false;
		}
		String name = defun.name();
		List<LispVal> parts = java.util.Objects.requireNonNull(primitive(defun));
		int objSlot = compileArgument(call, ctx);
		LispVal clear = clearOf(defun);
		if (clear != null) {
			WasmExprCompiler.compileForEffect(clear, ctx);
		}
		if (LispNames.OBJ_IS.equals(((LispSymbol) parts.get(0)).name())) {
			WasmInstanceCompiler.emitIsI32(ctx, objSlot, parts.subList(2, parts.size()));
			WasmEmitHelper.emitBoolFromI32(ctx);
			return true;
		}
		WasmLispCompiler.WasmFunctionInfo function = java.util.Objects.requireNonNull(ctx.functions.get(name));
		WasmInstanceCompiler.emitReadOr(ctx, objSlot, (int) ((LispInteger) parts.get(2)).value(), () -> {
			// Anything but an instance: the definition's own check and report.
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(objSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(ctx.uncelled.contains(name) ? function.funcIndex() : function.callIndex());
		});
		return true;
	}

	/**
	 * Compiles a call of a predicate in test position as the raw i32 of its type test
	 * (complemented when {@code negated}), or emits nothing for any other form. No clear:
	 * a test's value is its truth, and no consumer reads the multiple-value channel
	 * behind a test.
	 * @param call the test form
	 * @param ctx the compile context
	 * @param negated whether to answer the complement
	 * @return whether the test was compiled
	 */
	static boolean tryCompileTest(LispCons call, WasmLispCompiler.Ctx ctx, boolean negated) {
		WasmLispCompiler.DefunDecl defun = accessDefun(call, ctx);
		if (defun == null) {
			return false;
		}
		List<LispVal> parts = java.util.Objects.requireNonNull(primitive(defun));
		if (!LispNames.OBJ_IS.equals(((LispSymbol) parts.get(0)).name())) {
			return false;
		}
		WasmInstanceCompiler.emitIsI32(ctx, compileArgument(call, ctx), parts.subList(2, parts.size()));
		if (negated) {
			ctx.writer.write(Instruction.I32_EQZ);
		}
		return true;
	}

	private static int compileArgument(LispCons call, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(((LispCons) call.cdr()).car(), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		return slot;
	}

	// The (setq %mv-spill nil) of a settled body, or null when its tail clears nothing.
	private static @Nullable LispVal clearOf(WasmLispCompiler.DefunDecl defun) {
		for (LispVal expr : defun.bodyExprs()) {
			LispVal clear = findClear(expr);
			if (clear != null) {
				return clear;
			}
		}
		return null;
	}

	private static @Nullable LispVal findClear(LispVal form) {
		if (!(form instanceof LispCons cons) || !cons.isProperList() || isQuoted(form)) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() == 3 && parts.get(0) instanceof LispSymbol head && LispNames.SETQ.equals(head.name())
				&& parts.get(1) instanceof LispSymbol && parts.get(2) instanceof LispNil) {
			return form;
		}
		for (LispVal part : parts) {
			LispVal clear = findClear(part);
			if (clear != null) {
				return clear;
			}
		}
		return null;
	}

	private static WasmLispCompiler.@Nullable DefunDecl accessDefun(LispCons call, WasmLispCompiler.Ctx ctx) {
		if (ctx.structAccessDefuns.isEmpty() || !(call.car() instanceof LispSymbol head)
				|| !WasmIntFusionCompiler.speedTradesEnabled(ctx) || !WasmInstanceCompiler.instancesExist(ctx)) {
			return null;
		}
		String name = head.name();
		WasmLispCompiler.DefunDecl defun = ctx.structAccessDefuns.get(name);
		if (defun == null || !(call.cdr() instanceof LispCons rest) || !(rest.cdr() instanceof LispNil)) {
			return null;
		}
		WasmLispCompiler.WasmFunctionInfo function = ctx.functions.get(name);
		boolean replaceable = function == null || function.entryIndex() >= 0 && !ctx.uncelled.contains(name)
				|| ctx.fenvForwarders.contains(name);
		return replaceable ? null : defun;
	}

}
