package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code mapc} built-in function. Generates an inline loop that applies a
 * function to the parallel elements of one or more lists for its side effects, discards
 * the results, and leaves the FIRST list on the stack (Common Lisp {@code mapc}
 * semantics). With multiple lists the loop stops at the shortest one.
 */
final class JvmMapcCompiler {

	private JvmMapcCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// The inline walk below is a loop in expression position: its head must sit at
		// operand stack depth 0, or HotSpot refuses to OSR-compile the method
		// (JvmEmitHelper.inLoopScope).
		JvmEmitHelper.inLoopScope(ctx, () -> compileLoop(cons, ctx, className));
	}

	private static void compileLoop(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int nLists = args.size() - 2;
		if (nLists < 1) {
			throw new UnsupportedOperationException(LispNames.MAPC
					+ " expects at least 2 arguments (a function and one list), got " + (args.size() - 1));
		}
		// Compile the function designator: a literal one is called directly, anything
		// else goes through the arity dispatcher.
		JvmDesignatorCall call = JvmDesignatorCall.prepare(args.get(1), nLists, ctx, className);

		// Compile each list expression; the walk's end checks it is a list.
		List<Integer> listSlots = new ArrayList<>();
		for (int i = 0; i < nLists; i++) {
			JvmExprCompiler.compileExpr(args.get(2 + i), ctx, className);
			int listSlot = ctx.allocTemp();
			ctx.body.astore(listSlot);
			listSlots.add(listSlot);
		}

		// cursor_i = list_i (the first list is returned, so it keeps its own slot)
		List<Integer> cursorSlots = new ArrayList<>();
		for (int listSlot : listSlots) {
			ctx.body.aload(listSlot);
			int cursorSlot = ctx.allocTemp();
			ctx.body.astore(cursorSlot);
			cursorSlots.add(cursorSlot);
		}

		// loop:
		MethodCode.Label loopPos = ctx.body.newBoundLabel();
		// if any cursor is no cons, goto exit (stop at the shortest list)
		MethodCode.Label exit = ctx.body.newLabel();
		for (int cursorSlot : cursorSlots) {
			ctx.body.aload(cursorSlot);
			ctx.body.invokestatic(ctx.numOp(JvmOperandTypeRuntime.IS_CONS).entry());
			ctx.body.ifeq(exit);
		}

		// Call the function with the car of each cursor
		List<Runnable> cars = new ArrayList<>();
		for (int cursorSlot : cursorSlots) {
			cars.add(() -> emitCar(ctx, cursorSlot));
		}
		call.emitCall(ctx, className, cars);
		// Discard the mapped result
		ctx.body.pop();

		// advance each cursor: cursor = cdr(cursor) = ((Object[]) cursor)[1]
		for (int cursorSlot : cursorSlots) {
			ctx.body.aload(cursorSlot).checkcast(ctx.objectArrayClass.entry()).iconst_1().aaload();
			ctx.body.astore(cursorSlot);
		}

		// goto loop
		ctx.body.goto_(loopPos);

		// exit: load the first list
		ctx.body.labelBinding(exit);
		// Every cursor must be a list: nil ends one, any other atom -- an argument that
		// was no list, a dotted list's tail -- is the operator's type-error.
		for (int cursorSlot : cursorSlots) {
			ctx.body.aload(cursorSlot);
			JvmEmitHelper.emitListCheck(ctx);
			ctx.body.pop();
		}
		ctx.body.aload(listSlots.get(0));
	}

	// car(cursor) = ((Object[]) cursor)[0]
	private static void emitCar(JvmLispCompiler.Ctx ctx, int slot) {
		ctx.body.aload(slot).checkcast(ctx.objectArrayClass.entry()).iconst_0().aaload();
	}

}
