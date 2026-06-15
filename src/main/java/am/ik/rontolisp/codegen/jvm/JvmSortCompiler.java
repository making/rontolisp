package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code sort} built-in function. Generates an inline selection sort over
 * the cons cells of the list, swapping car values (not relinking) according to the
 * comparison predicate, so the original list head is returned in sorted order. Common
 * Lisp {@code sort} is destructive and not required to be stable.
 */
final class JvmSortCompiler {

	private JvmSortCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		ctx.indirectCallArities.add(2);

		// Compile list, then predicate (left-to-right evaluation order).
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int listSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(listSlot);

		JvmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(2)), ctx, className);
		int predSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(predSlot);

		int iSlot = ctx.allocTemp();
		int jSlot = ctx.allocTemp();
		int tmpSlot = ctx.allocTemp();

		// i = list
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(iSlot);

		// outerLoop:
		int outerPos = ctx.code.size();
		ctx.emit(Opcode.ALOAD);
		ctx.emit(iSlot);
		int outerEndBranch = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);

		// j = cdr(i)
		emitCdr(ctx, iSlot);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(jSlot);

		// innerLoop:
		int innerPos = ctx.code.size();
		ctx.emit(Opcode.ALOAD);
		ctx.emit(jSlot);
		int innerEndBranch = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);

		// if (_invoke_2(pred, car(j), car(i)) != nil) swap car(i) and car(j)
		ctx.emit(Opcode.ALOAD);
		ctx.emit(predSlot);
		emitCar(ctx, jSlot);
		emitCar(ctx, iSlot);
		JvmFunctionCallCompiler.emitDispatchCall(2, ctx, className);
		int noSwapBranch = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);

		// tmp = car(i)
		emitCar(ctx, iSlot);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(tmpSlot);
		// car(i) = car(j)
		ctx.emit(Opcode.ALOAD);
		ctx.emit(iSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		emitCar(ctx, jSlot);
		ctx.emit(Opcode.AASTORE);
		// car(j) = tmp
		ctx.emit(Opcode.ALOAD);
		ctx.emit(jSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tmpSlot);
		ctx.emit(Opcode.AASTORE);

		// noSwap:
		JvmEmitHelper.patchBranch(ctx, noSwapBranch, ctx.code.size());
		// j = cdr(j)
		emitCdr(ctx, jSlot);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(jSlot);
		// goto innerLoop
		int innerGoto = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2((innerPos - innerGoto) & 0xFFFF);

		// innerEnd:
		JvmEmitHelper.patchBranch(ctx, innerEndBranch, ctx.code.size());
		// i = cdr(i)
		emitCdr(ctx, iSlot);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(iSlot);
		// goto outerLoop
		int outerGoto = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2((outerPos - outerGoto) & 0xFFFF);

		// outerEnd:
		JvmEmitHelper.patchBranch(ctx, outerEndBranch, ctx.code.size());
		// result = list (original head, now sorted)
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
	}

	private static void emitCar(JvmLispCompiler.Ctx ctx, int slot) {
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
	}

	private static void emitCdr(JvmLispCompiler.Ctx ctx, int slot) {
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
	}

}
