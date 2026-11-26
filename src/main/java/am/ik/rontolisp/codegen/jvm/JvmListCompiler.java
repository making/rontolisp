package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.ArgumentOrder;
import am.ik.rontolisp.macro.LispMacroExpander;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the {@code list} and {@code list*} built-in functions.
 *
 * <p>
 * The cons chain has to be linked from the LAST element backwards, but Common Lisp
 * evaluates the argument forms left to right. The two orders are reconciled by evaluating
 * every effectful argument into a temp slot first, in source order, and linking the chain
 * from those temps ({@link ArgumentOrder}; {@code .kb/argument-evaluation-order.md}). A
 * constant argument needs no temp, so an all-literal {@code list} emits what it always
 * did. {@code list*} is the same chain linked onto its last argument instead of nil: one
 * loop, not a {@code cons} nested per argument, so its width costs no stack.
 */
final class JvmListCompiler {

	private JvmListCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons.toList(), false, ctx, className);
	}

	static void compileStar(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() <= 2) {
			// (list* x) is x; no argument is an error.
			JvmExprCompiler.compileExpr(LispMacroExpander.expandListStar(cons), ctx, className);
			return;
		}
		compile(args, true, ctx, className);
	}

	/**
	 * Links {@code args[1..]} into a fresh chain ending in nil, or for {@code star}
	 * ending in the value of the last argument, evaluated after every other.
	 */
	private static void compile(List<LispVal> args, boolean star, JvmLispCompiler.Ctx ctx, String className) {
		int end = star ? args.size() - 1 : args.size();
		// Left-to-right pre-evaluation of every effectful argument into a temp slot.
		List<@Nullable Integer> slots = new ArrayList<>(end);
		slots.add(null);
		for (int i = 1; i < end; i++) {
			if (ArgumentOrder.isOrderIndependent(args.get(i))) {
				slots.add(null);
				continue;
			}
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			int slot = ctx.allocTemp();
			ctx.body.astore(slot);
			slots.add(slot);
		}
		// One reused accumulator slot holds the tail while the next cons cell is built.
		int tailSlot = ctx.allocTemp();
		if (star) {
			JvmExprCompiler.compileExpr(args.get(end), ctx, className);
		}
		else {
			ctx.body.aconst_null();
		}
		for (int i = end - 1; i >= 1; i--) {
			ctx.body.astore(tailSlot).iconst_2().anewarray(ctx.objectClass).dup();
			ctx.body.iconst_0();
			Integer pre = slots.get(i);
			if (pre == null) {
				JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			}
			else {
				ctx.body.aload(pre);
			}
			ctx.body.aastore().dup().iconst_1().aload(tailSlot).aastore();
		}
	}

}
