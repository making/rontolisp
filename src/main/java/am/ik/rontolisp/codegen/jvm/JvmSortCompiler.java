package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code sort} built-in function when the program does not carry the shared
 * {@code %sort-runtime} merge sort -- which only a program that defines that name itself
 * does not (see {@code .kb/sort.md}). Generates an inline selection sort over the cons
 * cells of the list, swapping car values (not relinking) according to the comparison
 * predicate, so the original list head is returned in sorted order: correct, and
 * quadratic, which is why every other program calls the helper instead.
 */
final class JvmSortCompiler {

	private JvmSortCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// The inline walk below is a loop in expression position: its head must sit at
		// operand stack depth 0, or HotSpot refuses to OSR-compile the method
		// (JvmEmitHelper.inLoopScope).
		JvmEmitHelper.inLoopScope(ctx, () -> compileLoop(cons, ctx, className));
	}

	private static void compileLoop(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();

		// Compile list, then predicate (left-to-right evaluation order). A literal
		// predicate is called directly, anything else goes through the arity-2
		// dispatcher.
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int listSlot = ctx.allocTemp();
		ctx.body.astore(listSlot);

		JvmDesignatorCall call = JvmDesignatorCall.prepare(args.get(2), 2, ctx, className);

		int iSlot = ctx.allocTemp();
		int jSlot = ctx.allocTemp();
		int tmpSlot = ctx.allocTemp();

		// i = list
		ctx.body.aload(listSlot).astore(iSlot);

		// outerLoop:
		MethodCode.Label outerPos = ctx.body.newBoundLabel();
		ctx.body.aload(iSlot);
		MethodCode.Label outerEndBranch = ctx.body.newLabel();
		ctx.body.ifnull(outerEndBranch);

		// j = cdr(i)
		emitCdr(ctx, iSlot);
		ctx.body.astore(jSlot);

		// innerLoop:
		MethodCode.Label innerPos = ctx.body.newBoundLabel();
		ctx.body.aload(jSlot);
		MethodCode.Label innerEndBranch = ctx.body.newLabel();
		ctx.body.ifnull(innerEndBranch);

		// if (pred(car(j), car(i)) != nil) swap car(i) and car(j)
		call.emitCall(ctx, className, List.of(() -> emitCar(ctx, jSlot), () -> emitCar(ctx, iSlot)));
		MethodCode.Label noSwapBranch = ctx.body.newLabel();
		ctx.body.ifnull(noSwapBranch);

		// tmp = car(i)
		emitCar(ctx, iSlot);
		ctx.body.astore(tmpSlot);
		// car(i) = car(j)
		ctx.body.aload(iSlot).checkcast(ctx.objectArrayClass).iconst_0();
		emitCar(ctx, jSlot);
		ctx.body.aastore();
		// car(j) = tmp
		ctx.body.aload(jSlot).checkcast(ctx.objectArrayClass).iconst_0().aload(tmpSlot);
		ctx.body.aastore();

		// noSwap:
		ctx.body.labelBinding(noSwapBranch);
		// j = cdr(j)
		emitCdr(ctx, jSlot);
		ctx.body.astore(jSlot);
		// goto innerLoop
		ctx.body.goto_(innerPos);

		// innerEnd:
		ctx.body.labelBinding(innerEndBranch);
		// i = cdr(i)
		emitCdr(ctx, iSlot);
		ctx.body.astore(iSlot);
		// goto outerLoop
		ctx.body.goto_(outerPos);

		// outerEnd:
		ctx.body.labelBinding(outerEndBranch);
		// result = list (original head, now sorted)
		ctx.body.aload(listSlot);
	}

	private static void emitCar(JvmLispCompiler.Ctx ctx, int slot) {
		ctx.body.aload(slot).checkcast(ctx.objectArrayClass).iconst_0().aaload();
	}

	private static void emitCdr(JvmLispCompiler.Ctx ctx, int slot) {
		ctx.body.aload(slot).checkcast(ctx.objectArrayClass).iconst_1().aaload();
	}

}
