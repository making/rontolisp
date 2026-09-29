package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code mapcan} built-in function. Generates an inline loop that applies a
 * function to the parallel elements of one or more lists and concatenates the resulting
 * lists; with multiple lists the loop stops at the shortest one.
 *
 * <p>
 * The concatenation is a tail-pointer splice of a FRESH copy of each piece, not a left
 * fold over the shared {@code _append} helper: folding copied the whole accumulator per
 * piece (quadratic) through a call that itself recursed per element (linear stack depth),
 * so a long input list was a slow crash rather than a slow call (.todo/749). The result
 * is fully fresh, matching the interpreter's right fold piece for piece.
 */
final class JvmMapcanCompiler {

	private JvmMapcanCompiler() {
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
			throw new UnsupportedOperationException(LispNames.MAPCAN
					+ " expects at least 2 arguments (a function and one list), got " + (args.size() - 1));
		}
		// Compile the function designator: a literal one is called directly, anything
		// else goes through the arity dispatcher.
		JvmDesignatorCall call = JvmDesignatorCall.prepare(args.get(1), nLists, ctx, className);

		// Compile each list expression; the walk's end checks it is a list. The slots
		// double as the cursors -- only the concatenation is returned, so no list has to
		// survive the walk.
		List<Integer> listSlots = new ArrayList<>();
		for (int i = 0; i < nLists; i++) {
			JvmExprCompiler.compileExpr(args.get(2 + i), ctx, className);
			int listSlot = ctx.allocTemp();
			ctx.body.astore(listSlot);
			listSlots.add(listSlot);
		}

		// Sentinel head and its tail: every piece's fresh copy is linked here, so
		// the walk is linear in the total output rather than quadratic in it.
		ctx.body.iconst_2().anewarray(ctx.objectClass.entry());
		int headSlot = ctx.allocTemp();
		ctx.body.astore(headSlot).aload(headSlot);
		int tailSlot = ctx.allocTemp();
		ctx.body.astore(tailSlot);
		int cursorSlot = ctx.allocTemp();
		int freshSlot = ctx.allocTemp();

		// loop:
		MethodCode.Label loopPos = ctx.body.newBoundLabel();
		// if any list is no cons, goto exit (stop at the shortest list)
		MethodCode.Label exit = ctx.body.newLabel();
		for (int listSlot : listSlots) {
			ctx.body.aload(listSlot).invokestatic(ctx.numOp(JvmOperandTypeRuntime.IS_CONS).entry());
			ctx.body.ifeq(exit);
		}

		// mapped = func(car(list)...)
		List<Runnable> cars = new ArrayList<>();
		for (int listSlot : listSlots) {
			cars.add(() -> emitCar(ctx, listSlot));
		}
		call.emitCall(ctx, className, cars);
		int mappedSlot = ctx.allocTemp();
		ctx.body.astore(mappedSlot);

		// A piece that is no list is MAPCAN's type-error; a nil piece contributes
		// nothing, a cons is spliced in as a fresh copy below.
		ctx.body.aload(mappedSlot);
		JvmEmitHelper.emitListCheck(ctx);
		MethodCode.Label advance = ctx.body.newLabel();
		ctx.body.ifnull(advance);
		// cursor = mapped
		ctx.body.aload(mappedSlot).astore(cursorSlot);
		// piece loop: fresh = {car(cursor), null}; tail[1] = fresh; tail = fresh
		MethodCode.Label piecePos = ctx.body.newBoundLabel();
		MethodCode.Label pieceDone = ctx.body.newLabel();
		ctx.body.aload(cursorSlot).ifnull(pieceDone);
		ctx.body.iconst_2().anewarray(ctx.objectClass.entry()).dup().iconst_0().aload(cursorSlot);
		ctx.body.checkcast(ctx.objectArrayClass.entry()).iconst_0().aaload().aastore();
		ctx.body.astore(freshSlot).aload(tailSlot).checkcast(ctx.objectArrayClass.entry());
		ctx.body.iconst_1().aload(freshSlot).aastore().aload(freshSlot).astore(tailSlot);
		// cursor = cdr(cursor); goto piece loop
		ctx.body.aload(cursorSlot).checkcast(ctx.objectArrayClass.entry()).iconst_1().aaload();
		ctx.body.astore(cursorSlot).goto_(piecePos);
		ctx.body.labelBinding(pieceDone);
		ctx.body.labelBinding(advance);

		// advance each list: list = cdr(list) = ((Object[]) list)[1]
		for (int listSlot : listSlots) {
			ctx.body.aload(listSlot).checkcast(ctx.objectArrayClass.entry()).iconst_1().aaload();
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
		ctx.body.aload(headSlot).checkcast(ctx.objectArrayClass.entry()).iconst_1().aaload();
	}

	// car(list) = ((Object[]) list)[0]
	private static void emitCar(JvmLispCompiler.Ctx ctx, int slot) {
		ctx.body.aload(slot).checkcast(ctx.objectArrayClass.entry()).iconst_0().aaload();
	}

}
