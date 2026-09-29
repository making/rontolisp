package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code reduce} built-in function. Generates an inline loop that applies a
 * binary function to accumulate elements of a list into a single value (left fold).
 * Supports both 2-arg {@code (reduce f list)} and the Common Lisp keyword form
 * {@code (reduce f list :initial-value init)}.
 */
final class JvmReduceCompiler {

	private JvmReduceCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// The inline walk below is a loop in expression position: its head must sit at
		// operand stack depth 0, or HotSpot refuses to OSR-compile the method
		// (JvmEmitHelper.inLoopScope).
		JvmEmitHelper.inLoopScope(ctx, () -> compileLoop(cons, ctx, className));
	}

	private static void compileLoop(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		boolean withInit = hasInitialValue(args);

		// Compile the function designator: a literal one is called directly, anything
		// else goes through the arity-2 dispatcher.
		JvmDesignatorCall call = JvmDesignatorCall.prepare(args.get(1), 2, ctx, className);

		int accSlot = ctx.allocTemp();
		int listSlot = ctx.allocTemp();

		if (withInit) {
			// (reduce fn list :initial-value init)
			JvmExprCompiler.compileExpr(args.get(4), ctx, className);
			ctx.body.astore(accSlot);

			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			ctx.body.astore(listSlot);
		}
		else {
			// 2-arg: (reduce fn list) - first element becomes accumulator
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			ctx.body.astore(listSlot);

			// acc = car(list)
			ctx.body.aload(listSlot).checkcast(ctx.objectArrayClass.entry()).iconst_0().aaload();
			ctx.body.astore(accSlot);

			// list = cdr(list)
			ctx.body.aload(listSlot).checkcast(ctx.objectArrayClass.entry()).iconst_1().aaload();
			ctx.body.astore(listSlot);
		}

		// loop:
		MethodCode.Label loopPos = ctx.body.newBoundLabel();
		// if list == null, goto exit
		ctx.body.aload(listSlot);
		MethodCode.Label ifNullPos = ctx.body.newLabel();
		ctx.body.ifnull(ifNullPos);

		// acc = func(acc, car(list))
		call.emitCall(ctx, className, List.of(() -> {
			ctx.body.aload(accSlot);
		}, () -> {
			// car(list) = ((Object[]) list)[0]
			ctx.body.aload(listSlot).checkcast(ctx.objectArrayClass.entry()).iconst_0().aaload();
		}));
		ctx.body.astore(accSlot);

		// list = cdr(list) = ((Object[]) list)[1]
		ctx.body.aload(listSlot).checkcast(ctx.objectArrayClass.entry()).iconst_1().aaload();
		ctx.body.astore(listSlot);

		// goto loop
		ctx.body.goto_(loopPos);

		// exit: load accumulator
		ctx.body.labelBinding(ifNullPos);
		ctx.body.aload(accSlot);
	}

	/**
	 * Determines whether the {@code reduce} form carries a literal {@code :initial-value}
	 * keyword. Args are {@code [reduce, fn, list]} (2-arg) or
	 * {@code [reduce, fn, list, :initial-value, init]}.
	 * @param args the full reduce form parts
	 * @return {@code true} for the keyword initial-value form
	 */
	static boolean hasInitialValue(List<LispVal> args) {
		if (args.size() == 3) {
			return false;
		}
		if (args.size() == 5 && args.get(3) instanceof LispSymbol kw
				&& LispNames.INITIAL_VALUE_KEYWORD.equals(kw.name())) {
			return true;
		}
		throw new UnsupportedOperationException(
				"reduce expects (reduce fn list) or (reduce fn list :initial-value init)");
	}

}
