package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import org.jspecify.annotations.Nullable;

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
		String target = n >= 3 ? am.ik.rontolisp.macro.LispMacroExpander.applyLiteralTargetName(args.get(1)) : null;
		if (target != null) {
			JvmLispCompiler.FunctionInfo fi = ctx.functions.get(target);
			if (fi != null && fi.variadic() && n - 3 >= fi.positional()) {
				// Aligned: the leading arguments cover every parameter before the rest
				// list -- required and physical optional alike -- so the argument list
				// needs no build-then-unpack round trip: those parameters are the leading
				// expressions and the rest parameter takes the tail verbatim (or the
				// excess consed onto it), in source order.
				int positional = fi.positional();
				for (int i = 0; i < positional; i++) {
					JvmExprCompiler.compileExpr(args.get(2 + i), ctx, className);
				}
				JvmExprCompiler.compileExpr(
						am.ik.rontolisp.macro.LispMacroExpander.applyAlignedRestExpr(cons, positional), ctx, className);
				if (!am.ik.rontolisp.macro.LispMacroExpander.applyListProvablyProper(cons)) {
					// No count can be wrong here, but the tail still has to be a proper
					// list: _arityChk with the shape (0, variadic) walks it for that
					// alone.
					int tailSlot = ctx.allocTemp();
					ctx.body.astore(tailSlot);
					emitArityGuard(ctx, className, tailSlot, 0, true, null);
					ctx.body.aload(tailSlot);
				}
				ctx.body.invokestatic(fi.methodref().entry());
				return;
			}
			if (fi != null) {
				JvmExprCompiler.compileExpr(am.ik.rontolisp.macro.LispMacroExpander.applyArgumentListExpr(cons), ctx,
						className);
				int argsSlot = ctx.allocTemp();
				ctx.body.astore(argsSlot);
				// The count guard. This call reaches no dispatcher, so no no-match arm
				// can report a wrong count for it, and the walk below is car/cdr -- a
				// short list would BIND nil for the parameters it does not reach and a
				// long one would drop its tail. _arityChk measures the list against the
				// shape baked here and throws ClosRegistry.arityMessage's text, the same
				// helper a SPREAD dispatcher case carries.
				emitArityGuard(ctx, className, argsSlot, fi.required(), fi.variadic(), target);
				// The parameters out of the list: an optional past its end is the
				// UNSUPPLIED marker, and the rest list is the tail past the optionals.
				JvmPhysicalArgs.emitFromList(ctx, className, fi, argsSlot);
				ctx.body.invokestatic(fi.methodref().entry());
				return;
			}
		}

		// Compile the function designator.
		JvmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx, className);
		int funcSlot = ctx.allocTemp();
		ctx.body.astore(funcSlot);

		// Compile the leading literal arguments (indices 2 .. n-2), left to right.
		List<Integer> argSlots = new ArrayList<>();
		for (int i = 2; i < n - 1; i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			int s = ctx.allocTemp();
			ctx.body.astore(s);
			argSlots.add(s);
		}

		// Compile the final list argument; it becomes the tail of the argument list.
		JvmExprCompiler.compileExpr(args.get(n - 1), ctx, className);
		int curSlot = ctx.allocTemp();
		ctx.body.astore(curSlot);

		// Prepend each leading argument: cur = new Object[]{arg, cur}.
		for (int k = argSlots.size() - 1; k >= 0; k--) {
			ctx.body.iconst_2().anewarray(ctx.objectClass.entry()).dup().iconst_0();
			ctx.body.aload(argSlots.get(k)).aastore().dup().iconst_1().aload(curSlot).aastore();
			ctx.body.astore(curSlot);
		}

		// _apply(func, argList)
		ctx.body.aload(funcSlot).aload(curSlot);
		MethodrefConstant applyRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8("_apply"),
						ctx.cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")));
		ctx.body.invokestatic(applyRef.entry());
	}

	/**
	 * Emits {@code _arityChk(args, shape)} in front of the physical direct call, and
	 * records the shape so the emitter knows the helper is reachable
	 * ({@code JvmLispCompiler.Ctx.arityGuardShapes}). The shape carries the callee's
	 * operator when it is a built-in ({@code JvmArityOperators}).
	 */
	private static void emitArityGuard(JvmLispCompiler.Ctx ctx, String className, int argsSlot, int required,
			boolean variadic, @Nullable String target) {
		int shape = ctx.arityOperators.shape(required, variadic, target);
		ctx.arityGuardShapes.add(shape);
		MethodrefConstant chkRef = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8(JvmRuntimeBuilder.ARITY_CHK_NAME),
						ctx.cp.addUtf8(JvmRuntimeBuilder.ARITY_CHK_DESC)));
		ctx.body.aload(argsSlot);
		JvmEmitHelper.emitIntConst(ctx, shape);
		ctx.body.invokestatic(chkRef.entry());
	}

}
