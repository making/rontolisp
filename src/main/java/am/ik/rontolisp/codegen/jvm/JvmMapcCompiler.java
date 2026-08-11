package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code mapc} built-in function. Generates an inline loop that applies a
 * function to the parallel elements of one or more lists for its side effects, discards
 * the results, and leaves the FIRST list on the stack (Common Lisp {@code mapc}
 * semantics). With multiple lists the loop stops at the shortest one.
 */
final class JvmMapcCompiler {

	private JvmMapcCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int nLists = args.size() - 2;
		if (nLists < 1) {
			throw new UnsupportedOperationException(LispNames.MAPC
					+ " expects at least 2 arguments (a function and one list), got " + (args.size() - 1));
		}
		// Compile the function designator: a literal one is called directly, anything
		// else goes through the arity dispatcher.
		JvmDesignatorCall call = JvmDesignatorCall.prepare(args.get(1), nLists, ctx, className);

		// Compile each list expression, guarding it is a list. mapc operates on lists; a
		// non-list (e.g. a string) signals an error.
		List<Integer> listSlots = new ArrayList<>();
		for (int i = 0; i < nLists; i++) {
			JvmExprCompiler.compileExpr(args.get(2 + i), ctx, className);
			int listSlot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(listSlot);
			JvmEmitHelper.emitRequireListGuard(ctx, listSlot,
					"MAPC: argument is not a list (use map for strings/vectors)");
			listSlots.add(listSlot);
		}

		// cursor_i = list_i (the first list is returned, so it keeps its own slot)
		List<Integer> cursorSlots = new ArrayList<>();
		for (int listSlot : listSlots) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(listSlot);
			int cursorSlot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(cursorSlot);
			cursorSlots.add(cursorSlot);
		}

		// loop:
		int loopPos = ctx.code.size();
		// if any cursor == null, goto exit (stop at the shortest list)
		List<Integer> exitBranches = new ArrayList<>();
		for (int cursorSlot : cursorSlots) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(cursorSlot);
			exitBranches.add(ctx.code.size());
			ctx.emit(Opcode.IFNULL);
			ctx.emitU2(0);
		}

		// Call the function with the car of each cursor
		List<Runnable> cars = new ArrayList<>();
		for (int cursorSlot : cursorSlots) {
			cars.add(() -> emitCar(ctx, cursorSlot));
		}
		call.emitCall(ctx, className, cars);
		// Discard the mapped result
		ctx.emit(Opcode.POP);

		// advance each cursor: cursor = cdr(cursor) = ((Object[]) cursor)[1]
		for (int cursorSlot : cursorSlots) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(cursorSlot);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.AALOAD);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(cursorSlot);
		}

		// goto loop
		int gotoPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		int offset = loopPos - gotoPos;
		ctx.emitU2(offset & 0xFFFF);

		// exit: load the first list
		int exitPos = ctx.code.size();
		for (int branchPos : exitBranches) {
			JvmEmitHelper.patchBranch(ctx, branchPos, exitPos);
		}
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlots.get(0));
	}

	// car(cursor) = ((Object[]) cursor)[0]
	private static void emitCar(JvmLispCompiler.Ctx ctx, int slot) {
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
	}

}
