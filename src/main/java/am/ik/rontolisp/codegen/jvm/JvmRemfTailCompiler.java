package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code %remf-tail} built-in function. Walks a property list starting from
 * the first key, looking ahead at the next key-value pair. When a matching key is found,
 * splices it out by mutating the cdr of the preceding value cell.
 */
final class JvmRemfTailCompiler {

	private JvmRemfTailCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// The inline walk below is a loop in expression position: its head must sit at
		// operand stack depth 0, or HotSpot refuses to OSR-compile the method
		// (JvmEmitHelper.inLoopScope).
		JvmEmitHelper.inLoopScope(ctx, () -> compileLoop(cons, ctx, className));
	}

	private static void compileLoop(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		// Evaluate plist
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int currentSlot = ctx.allocTemp();
		ctx.body.astore(currentSlot);
		// Evaluate indicator
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		int indicatorSlot = ctx.allocTemp();
		ctx.body.astore(indicatorSlot);

		int valueCellSlot = ctx.allocTemp();
		int nextKeyCellSlot = ctx.allocTemp();

		// loop:
		MethodCode.Label loopPos = ctx.body.newBoundLabel();
		// Check current instanceof Object[] (cons)
		ctx.body.aload(currentSlot).instanceOf(ctx.objectArrayClass.entry());
		MethodCode.Label ifNotConsPos = ctx.body.newLabel();
		ctx.body.ifeq(ifNotConsPos);

		// valueCell = cdr(current) = ((Object[])current)[1]
		ctx.body.aload(currentSlot).checkcast(ctx.objectArrayClass.entry()).iconst_1().aaload();
		ctx.body.astore(valueCellSlot);

		// Check valueCell instanceof Object[]
		ctx.body.aload(valueCellSlot).instanceOf(ctx.objectArrayClass.entry());
		MethodCode.Label ifValNotConsPos = ctx.body.newLabel();
		ctx.body.ifeq(ifValNotConsPos);

		// nextKeyCell = cdr(valueCell) = ((Object[])valueCell)[1]
		ctx.body.aload(valueCellSlot).checkcast(ctx.objectArrayClass.entry()).iconst_1().aaload();
		ctx.body.astore(nextKeyCellSlot);

		// Check nextKeyCell instanceof Object[]
		ctx.body.aload(nextKeyCellSlot).instanceOf(ctx.objectArrayClass.entry());
		MethodCode.Label ifNextNotConsPos = ctx.body.newLabel();
		ctx.body.ifeq(ifNextNotConsPos);

		// Compare car(nextKeyCell) with indicator
		// car(nextKeyCell) = ((Object[])nextKeyCell)[0]
		ctx.body.aload(nextKeyCellSlot).checkcast(ctx.objectArrayClass.entry()).iconst_0().aaload();
		ctx.body.aload(indicatorSlot).invokevirtual(ctx.objectEquals.methodRefEntry());
		MethodCode.Label ifNoMatchPos = ctx.body.newLabel();
		ctx.body.ifeq(ifNoMatchPos);

		// Match! splice: rplacd(valueCell, cddr(nextKeyCell))
		// ((Object[])valueCell)[1] = ((Object[])((Object[])nextKeyCell)[1])[1]
		ctx.body.aload(valueCellSlot).checkcast(ctx.objectArrayClass.entry()).iconst_1();
		// Compute cddr(nextKeyCell)
		ctx.body.aload(nextKeyCellSlot).checkcast(ctx.objectArrayClass.entry()).iconst_1();
		ctx.body.aaload(); // cdr(nextKeyCell)
		ctx.body.checkcast(ctx.objectArrayClass.entry()).iconst_1();
		ctx.body.aaload(); // cddr(nextKeyCell)
		ctx.body.aastore();
		// Return t = Long(1)
		JvmEmitHelper.compileTrue(ctx);
		MethodCode.Label gotoEndPos = ctx.body.newLabel();
		ctx.body.goto_(gotoEndPos);

		// No match: current = nextKeyCell, continue loop
		ctx.body.labelBinding(ifNoMatchPos);
		ctx.body.aload(nextKeyCellSlot).astore(currentSlot).goto_(loopPos);

		// return nil
		ctx.body.labelBinding(ifNotConsPos);
		ctx.body.labelBinding(ifValNotConsPos);
		ctx.body.labelBinding(ifNextNotConsPos);
		ctx.body.aconst_null();

		// end
		ctx.body.labelBinding(gotoEndPos);
	}

}
