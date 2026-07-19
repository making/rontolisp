package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code nthcdr} built-in function. Generates a loop that applies
 * {@code cdr} n times to the list argument.
 */
final class JvmNthcdrCompiler {

	private JvmNthcdrCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		// Evaluate n -> Long, unbox to long, convert to int
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxLong(ctx);
		ctx.emit(Opcode.L2I);
		int nSlot = ctx.allocTemp();
		ctx.emit(Opcode.ISTORE);
		ctx.emit(nSlot);
		// Evaluate list
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		int listSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(listSlot);
		// loop: if n <= 0, exit
		int loopPos = ctx.code.size();
		ctx.emit(Opcode.ILOAD);
		ctx.emit(nSlot);
		int ifLePos = ctx.code.size();
		ctx.emit(Opcode.IFLE);
		ctx.emitU2(0);
		// nil short-circuits: (nthcdr n lst) past the end is nil, like CL.
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		// list = ((Object[]) list)[1] (cdr)
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(listSlot);
		// n = n - 1
		ctx.emit(Opcode.IINC);
		ctx.emit(nSlot);
		ctx.emit(0xFF); // -1 as unsigned byte
		// goto loop
		int gotoPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		int offset = loopPos - gotoPos;
		ctx.emitU2(offset & 0xFFFF);
		// exit: load list
		JvmEmitHelper.patchBranch(ctx, ifLePos, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
	}

}
