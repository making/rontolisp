package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code mapcar} built-in function. Generates an inline loop that applies a
 * function to the parallel elements of one or more lists, building a new list using the
 * sentinel/tail-mutation pattern. With multiple lists the loop stops at the shortest list
 * (Common Lisp semantics).
 */
final class JvmMapcarCompiler {

	private JvmMapcarCompiler() {
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
			throw new UnsupportedOperationException(LispNames.MAPCAR
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

		// Create sentinel cons: new Object[2] {null, null}
		ctx.body.iconst_2().anewarray(ctx.objectClass);
		int headSlot = ctx.allocTemp();
		ctx.body.astore(headSlot);

		// tail = head (initially points to sentinel)
		ctx.body.aload(headSlot);
		int tailSlot = ctx.allocTemp();
		ctx.body.astore(tailSlot);

		// loop:
		MethodCode.Label loopPos = ctx.body.newBoundLabel();
		// if any list is no cons, goto exit (stop at the shortest list)
		MethodCode.Label exit = ctx.body.newLabel();
		for (int listSlot : listSlots) {
			ctx.body.aload(listSlot).invokestatic(ctx.numOp(JvmOperandTypeRuntime.IS_CONS));
			ctx.body.ifeq(exit);
		}

		// Call the function with the car of each list
		List<Runnable> cars = new ArrayList<>();
		for (int listSlot : listSlots) {
			cars.add(() -> emitCar(ctx, listSlot));
		}
		call.emitCall(ctx, className, cars);

		// Create new cons: new Object[2] {mapped, null}
		int mappedSlot = ctx.allocTemp();
		ctx.body.astore(mappedSlot).iconst_2().anewarray(ctx.objectClass).dup().iconst_0();
		ctx.body.aload(mappedSlot).aastore();

		int newConsSlot = ctx.allocTemp();
		ctx.body.astore(newConsSlot);

		// tail[1] = newCons (rplacd tail)
		ctx.body.aload(tailSlot).checkcast(ctx.objectArrayClass).iconst_1();
		ctx.body.aload(newConsSlot).aastore();

		// tail = newCons
		ctx.body.aload(newConsSlot).astore(tailSlot);

		// advance each list: list = cdr(list) = ((Object[]) list)[1]
		for (int listSlot : listSlots) {
			ctx.body.aload(listSlot).checkcast(ctx.objectArrayClass).iconst_1().aaload();
			ctx.body.astore(listSlot);
		}

		// goto loop
		ctx.body.goto_(loopPos);

		// exit: load head[1] (cdr of sentinel = first real cons or null)
		ctx.body.labelBinding(exit);
		// Every cursor must be a list: nil ends one, any other atom -- an argument that
		// was no list, a dotted list's tail -- is the operator's type-error.
		for (int listSlot : listSlots) {
			ctx.body.aload(listSlot);
			JvmEmitHelper.emitListCheck(ctx);
			ctx.body.pop();
		}
		ctx.body.aload(headSlot).checkcast(ctx.objectArrayClass).iconst_1().aaload();
	}

	// car(list) = ((Object[]) list)[0]
	private static void emitCar(JvmLispCompiler.Ctx ctx, int slot) {
		ctx.body.aload(slot).checkcast(ctx.objectArrayClass).iconst_0().aaload();
	}

}
