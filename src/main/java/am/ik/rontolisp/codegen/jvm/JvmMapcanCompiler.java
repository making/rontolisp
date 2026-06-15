package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code mapcan} built-in function. Generates an inline loop that applies a
 * function to each element of a list and concatenates the resulting lists. The
 * concatenation reuses the shared {@code _append} runtime helper (non-destructive append
 * rather than nconc).
 */
final class JvmMapcanCompiler {

	private JvmMapcanCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		ctx.indirectCallArities.add(1);

		// Compile function expression
		JvmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx, className);
		int funcSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(funcSlot);

		// Compile list expression
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		int listSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(listSlot);

		// result = null
		int resultSlot = ctx.allocTemp();
		ctx.emit(Opcode.ACONST_NULL);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(resultSlot);

		// loop:
		int loopPos = ctx.code.size();
		// if list == null, goto exit
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);

		// mapped = _invoke_1(func, car(list))
		ctx.emit(Opcode.ALOAD);
		ctx.emit(funcSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
		JvmFunctionCallCompiler.emitDispatchCall(1, ctx, className);
		int mappedSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(mappedSlot);

		// result = _append(result, mapped)
		ctx.emit(Opcode.ALOAD);
		ctx.emit(resultSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(mappedSlot);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.appendMethod.index());
		ctx.emit(Opcode.ASTORE);
		ctx.emit(resultSlot);

		// list = cdr(list) = ((Object[]) list)[1]
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(listSlot);

		// goto loop
		int gotoPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		int offset = loopPos - gotoPos;
		ctx.emitU2(offset & 0xFFFF);

		// exit: load result
		JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(resultSlot);
	}

}
