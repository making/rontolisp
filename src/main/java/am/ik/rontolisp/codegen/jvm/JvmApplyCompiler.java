package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code apply} built-in function. The leading arguments are taken literally
 * and the final argument is a list whose elements are spread; the full argument list is
 * built as {@code (cons arg1 (cons ... lastList))} and passed to the runtime
 * {@code _apply} helper. Using {@code apply} forces the eval runtime to be emitted (see
 * {@code JvmLispCompiler}), which provides {@code _apply}.
 */
final class JvmApplyCompiler {

	private JvmApplyCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
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
			JvmLispCompiler.FunctionInfo fi = ctx.functions.get(target);
			if (fi != null) {
				JvmExprCompiler.compileExpr(am.ik.rontolisp.LispMacroExpander.applyArgumentListExpr(cons), ctx,
						className);
				int argsSlot = ctx.allocTemp();
				ctx.emit(Opcode.ASTORE);
				ctx.emit(argsSlot);
				int required = fi.variadic() ? fi.paramCount() - 1 : fi.paramCount();
				for (int i = 0; i < required; i++) {
					ctx.emit(Opcode.ALOAD);
					ctx.emit(argsSlot);
					for (int step = 0; step < i; step++) {
						emitNullSafeCell(ctx, 1);
					}
					emitNullSafeCell(ctx, 0);
				}
				if (fi.variadic()) {
					ctx.emit(Opcode.ALOAD);
					ctx.emit(argsSlot);
					for (int step = 0; step < required; step++) {
						emitNullSafeCell(ctx, 1);
					}
				}
				ctx.emit(Opcode.INVOKESTATIC);
				ctx.emitU2(fi.methodref().index());
				return;
			}
		}

		// Compile the function designator.
		JvmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx, className);
		int funcSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(funcSlot);

		// Compile the leading literal arguments (indices 2 .. n-2), left to right.
		List<Integer> argSlots = new ArrayList<>();
		for (int i = 2; i < n - 1; i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			int s = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(s);
			argSlots.add(s);
		}

		// Compile the final list argument; it becomes the tail of the argument list.
		JvmExprCompiler.compileExpr(args.get(n - 1), ctx, className);
		int curSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(curSlot);

		// Prepend each leading argument: cur = new Object[]{arg, cur}.
		for (int k = argSlots.size() - 1; k >= 0; k--) {
			ctx.emit(Opcode.ICONST_2);
			ctx.emit(Opcode.ANEWARRAY);
			ctx.emitU2(ctx.objectClass.index());
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(argSlots.get(k));
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(curSlot);
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(curSlot);
		}

		// _apply(func, argList)
		ctx.emit(Opcode.ALOAD);
		ctx.emit(funcSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(curSlot);
		MethodrefConstant applyRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8("_apply"),
						ctx.cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(applyRef.index());
	}

	// Replaces the cons on the stack with its car (field 0) or cdr (field 1); nil
	// passes through, like the car/cdr built-ins.
	private static void emitNullSafeCell(JvmLispCompiler.Ctx ctx, int field) {
		ctx.emit(Opcode.DUP);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(field == 0 ? Opcode.ICONST_0 : Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
		JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
	}

}
