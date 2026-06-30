package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code mapc} built-in function. Generates an inline loop that applies a
 * function to each element of a list for its side effects, discards the results, and
 * leaves the original list on the stack (Common Lisp {@code mapc} semantics).
 */
final class JvmMapcCompiler {

	private JvmMapcCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		ctx.indirectCallArities.add(1);

		// Compile function expression
		JvmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx, className);
		int funcSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(funcSlot);

		// Compile list expression (kept in listSlot, returned at the end)
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		int listSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(listSlot);

		// mapc operates on lists; a non-list (e.g. a string) signals an error.
		JvmEmitHelper.emitRequireListGuard(ctx, listSlot, "mapc: argument is not a list (use map for strings/vectors)");

		// cursor = list
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
		int cursorSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(cursorSlot);

		// loop:
		int loopPos = ctx.code.size();
		// if cursor == null, goto exit
		ctx.emit(Opcode.ALOAD);
		ctx.emit(cursorSlot);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);

		// Push func and car(cursor) for dispatch call
		ctx.emit(Opcode.ALOAD);
		ctx.emit(funcSlot);
		// car(cursor) = ((Object[]) cursor)[0]
		ctx.emit(Opcode.ALOAD);
		ctx.emit(cursorSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
		// Call _invoke_1(func, car)
		JvmFunctionCallCompiler.emitDispatchCall(1, ctx, className);
		// Discard the mapped result
		ctx.emit(Opcode.POP);

		// cursor = cdr(cursor) = ((Object[]) cursor)[1]
		ctx.emit(Opcode.ALOAD);
		ctx.emit(cursorSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(cursorSlot);

		// goto loop
		int gotoPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		int offset = loopPos - gotoPos;
		ctx.emitU2(offset & 0xFFFF);

		// exit: load the original list
		JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
	}

}
