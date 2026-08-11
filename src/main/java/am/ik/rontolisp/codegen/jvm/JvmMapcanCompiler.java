package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code mapcan} built-in function. Generates an inline loop that applies a
 * function to the parallel elements of one or more lists and concatenates the resulting
 * lists; with multiple lists the loop stops at the shortest one. The concatenation reuses
 * the shared {@code _append} runtime helper (non-destructive append rather than nconc).
 */
final class JvmMapcanCompiler {

	private JvmMapcanCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int nLists = args.size() - 2;
		if (nLists < 1) {
			throw new UnsupportedOperationException(LispNames.MAPCAN
					+ " expects at least 2 arguments (a function and one list), got " + (args.size() - 1));
		}
		// Compile the function designator: a literal one is called directly, anything
		// else goes through the arity dispatcher.
		JvmDesignatorCall call = JvmDesignatorCall.prepare(args.get(1), nLists, ctx, className);

		// Compile each list expression, guarding it is a list. mapcan operates on lists;
		// a
		// non-list (e.g. a string) signals an error. The slots double as the cursors --
		// only the concatenation is returned, so no list has to survive the walk.
		List<Integer> listSlots = new ArrayList<>();
		for (int i = 0; i < nLists; i++) {
			JvmExprCompiler.compileExpr(args.get(2 + i), ctx, className);
			int listSlot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(listSlot);
			JvmEmitHelper.emitRequireListGuard(ctx, listSlot,
					"MAPCAN: argument is not a list (use map for strings/vectors)");
			listSlots.add(listSlot);
		}

		// result = null
		int resultSlot = ctx.allocTemp();
		ctx.emit(Opcode.ACONST_NULL);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(resultSlot);

		// loop:
		int loopPos = ctx.code.size();
		// if any list == null, goto exit (stop at the shortest list)
		List<Integer> exitBranches = new ArrayList<>();
		for (int listSlot : listSlots) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(listSlot);
			exitBranches.add(ctx.code.size());
			ctx.emit(Opcode.IFNULL);
			ctx.emitU2(0);
		}

		// mapped = func(car(list)...)
		List<Runnable> cars = new ArrayList<>();
		for (int listSlot : listSlots) {
			cars.add(() -> emitCar(ctx, listSlot));
		}
		call.emitCall(ctx, className, cars);
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

		// advance each list: list = cdr(list) = ((Object[]) list)[1]
		for (int listSlot : listSlots) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(listSlot);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.AALOAD);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(listSlot);
		}

		// goto loop
		int gotoPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		int offset = loopPos - gotoPos;
		ctx.emitU2(offset & 0xFFFF);

		// exit: load result
		int exitPos = ctx.code.size();
		for (int branchPos : exitBranches) {
			JvmEmitHelper.patchBranch(ctx, branchPos, exitPos);
		}
		ctx.emit(Opcode.ALOAD);
		ctx.emit(resultSlot);
	}

	// car(list) = ((Object[]) list)[0]
	private static void emitCar(JvmLispCompiler.Ctx ctx, int slot) {
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
	}

}
