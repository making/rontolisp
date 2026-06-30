package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code mapcar} built-in function. Generates an inline loop that applies a
 * function to each element of a list, building a new list using the
 * sentinel/tail-mutation pattern.
 */
final class JvmMapcarCompiler {

	private JvmMapcarCompiler() {
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

		// mapcar operates on lists; a non-list (e.g. a string) signals an error.
		JvmEmitHelper.emitRequireListGuard(ctx, listSlot,
				"mapcar: argument is not a list (use map for strings/vectors)");

		// Create sentinel cons: new Object[2] {null, null}
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		int headSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(headSlot);

		// tail = head (initially points to sentinel)
		ctx.emit(Opcode.ALOAD);
		ctx.emit(headSlot);
		int tailSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(tailSlot);

		// loop:
		int loopPos = ctx.code.size();
		// if list == null, goto exit
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);

		// Push func and car(list) for dispatch call
		ctx.emit(Opcode.ALOAD);
		ctx.emit(funcSlot);
		// car(list) = ((Object[]) list)[0]
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
		// Call _invoke_1(func, car)
		JvmFunctionCallCompiler.emitDispatchCall(1, ctx, className);

		// Create new cons: new Object[2] {mapped, null}
		int mappedSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(mappedSlot);
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(mappedSlot);
		ctx.emit(Opcode.AASTORE);

		int newConsSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(newConsSlot);

		// tail[1] = newCons (rplacd tail)
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tailSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(newConsSlot);
		ctx.emit(Opcode.AASTORE);

		// tail = newCons
		ctx.emit(Opcode.ALOAD);
		ctx.emit(newConsSlot);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(tailSlot);

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

		// exit: load head[1] (cdr of sentinel = first real cons or null)
		JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(headSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
	}

}
