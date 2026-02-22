package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code %remf-tail} built-in function. Walks a property list starting from
 * the first key, looking ahead at the next key-value pair. When a matching key is found,
 * splices it out by mutating the cdr of the preceding value cell.
 */
final class JvmRemfTailCompiler {

	private JvmRemfTailCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		// Evaluate plist
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int currentSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(currentSlot);
		// Evaluate indicator
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		int indicatorSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(indicatorSlot);

		int valueCellSlot = ctx.allocTemp();
		int nextKeyCellSlot = ctx.allocTemp();

		// loop:
		int loopPos = ctx.code.size();
		// Check current instanceof Object[] (cons)
		ctx.emit(Opcode.ALOAD);
		ctx.emit(currentSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.objectArrayClass.index());
		int ifNotConsPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);

		// valueCell = cdr(current) = ((Object[])current)[1]
		ctx.emit(Opcode.ALOAD);
		ctx.emit(currentSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(valueCellSlot);

		// Check valueCell instanceof Object[]
		ctx.emit(Opcode.ALOAD);
		ctx.emit(valueCellSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.objectArrayClass.index());
		int ifValNotConsPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);

		// nextKeyCell = cdr(valueCell) = ((Object[])valueCell)[1]
		ctx.emit(Opcode.ALOAD);
		ctx.emit(valueCellSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(nextKeyCellSlot);

		// Check nextKeyCell instanceof Object[]
		ctx.emit(Opcode.ALOAD);
		ctx.emit(nextKeyCellSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.objectArrayClass.index());
		int ifNextNotConsPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);

		// Compare car(nextKeyCell) with indicator
		// car(nextKeyCell) = ((Object[])nextKeyCell)[0]
		ctx.emit(Opcode.ALOAD);
		ctx.emit(nextKeyCellSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(indicatorSlot);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.objectEquals.index());
		int ifNoMatchPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);

		// Match! splice: rplacd(valueCell, cddr(nextKeyCell))
		// ((Object[])valueCell)[1] = ((Object[])((Object[])nextKeyCell)[1])[1]
		ctx.emit(Opcode.ALOAD);
		ctx.emit(valueCellSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		// Compute cddr(nextKeyCell)
		ctx.emit(Opcode.ALOAD);
		ctx.emit(nextKeyCellSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD); // cdr(nextKeyCell)
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD); // cddr(nextKeyCell)
		ctx.emit(Opcode.AASTORE);
		// Return t = Long(1)
		JvmEmitHelper.compileLong(1, ctx);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);

		// No match: current = nextKeyCell, continue loop
		JvmEmitHelper.patchBranch(ctx, ifNoMatchPos, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(nextKeyCellSlot);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(currentSlot);
		int gotoLoopPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		int offset = loopPos - gotoLoopPos;
		ctx.emitU2(offset & 0xFFFF);

		// return nil
		JvmEmitHelper.patchBranch(ctx, ifNotConsPos, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, ifValNotConsPos, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, ifNextNotConsPos, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);

		// end
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

}
