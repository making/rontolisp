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
 * lists; with multiple lists the loop stops at the shortest one.
 *
 * <p>
 * The concatenation is a tail-pointer splice of a FRESH copy of each piece, not a left
 * fold over the shared {@code _append} helper: folding copied the whole accumulator per
 * piece (quadratic) through a call that itself recursed per element (linear stack depth),
 * so a long input list was a slow crash rather than a slow call (.todo/749). The result
 * is fully fresh, matching the interpreter's right fold piece for piece.
 */
final class JvmMapcanCompiler {

	private JvmMapcanCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// The inline walk below is a loop in expression position: its head must sit at
		// operand stack depth 0, or HotSpot refuses to OSR-compile the method
		// (JvmEmitHelper.inLoopScope).
		JvmEmitHelper.inLoopScope(ctx, () -> compileLoop(cons, ctx, className));
	}

	private static void compileLoop(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int nLists = args.size() - 2;
		if (nLists < 1) {
			throw new UnsupportedOperationException(LispNames.MAPCAN
					+ " expects at least 2 arguments (a function and one list), got " + (args.size() - 1));
		}
		// Compile the function designator: a literal one is called directly, anything
		// else goes through the arity dispatcher.
		JvmDesignatorCall call = JvmDesignatorCall.prepare(args.get(1), nLists, ctx, className);

		// Compile each list expression; the walk's end checks it is a list. The slots
		// double as the cursors -- only the concatenation is returned, so no list has to
		// survive the walk.
		List<Integer> listSlots = new ArrayList<>();
		for (int i = 0; i < nLists; i++) {
			JvmExprCompiler.compileExpr(args.get(2 + i), ctx, className);
			int listSlot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(listSlot);
			listSlots.add(listSlot);
		}

		// Sentinel head and its tail: every piece's fresh copy is linked here, so
		// the walk is linear in the total output rather than quadratic in it.
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		int headSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(headSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(headSlot);
		int tailSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(tailSlot);
		int cursorSlot = ctx.allocTemp();
		int freshSlot = ctx.allocTemp();

		// loop:
		int loopPos = ctx.code.size();
		// if any list is no cons, goto exit (stop at the shortest list)
		List<Integer> exitBranches = new ArrayList<>();
		for (int listSlot : listSlots) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(listSlot);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.numOp(JvmOperandTypeRuntime.IS_CONS).index());
			exitBranches.add(ctx.code.size());
			ctx.emit(Opcode.IFEQ);
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

		// A piece that is no list is MAPCAN's type-error; a nil piece contributes
		// nothing, a cons is spliced in as a fresh copy below.
		ctx.emit(Opcode.ALOAD);
		ctx.emit(mappedSlot);
		JvmEmitHelper.emitListCheck(ctx);
		int skipPiecePos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		// cursor = mapped
		ctx.emit(Opcode.ALOAD);
		ctx.emit(mappedSlot);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(cursorSlot);
		// piece loop: fresh = {car(cursor), null}; tail[1] = fresh; tail = fresh
		int piecePos = ctx.code.size();
		ctx.emit(Opcode.ALOAD);
		ctx.emit(cursorSlot);
		int pieceDonePos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.ICONST_2);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(cursorSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(freshSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(tailSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(freshSlot);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(freshSlot);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(tailSlot);
		// cursor = cdr(cursor); goto piece loop
		ctx.emit(Opcode.ALOAD);
		ctx.emit(cursorSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(cursorSlot);
		int pieceAgainPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		int pieceOffset = piecePos - pieceAgainPos;
		ctx.emitU2(pieceOffset & 0xFFFF);
		int pieceDoneTarget = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, pieceDonePos, pieceDoneTarget);
		int advancePos = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, skipPiecePos, advancePos);

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

		// exit: load head[1] (cdr of sentinel = first real cons or null)
		int exitPos = ctx.code.size();
		for (int branchPos : exitBranches) {
			JvmEmitHelper.patchBranch(ctx, branchPos, exitPos);
		}
		// Every cursor must be a list: nil ends one, any other atom -- an argument that
		// was no list, a dotted list's tail -- is the operator's type-error.
		for (int listSlot : listSlots) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(listSlot);
			JvmEmitHelper.emitListCheck(ctx);
			ctx.emit(Opcode.POP);
		}
		ctx.emit(Opcode.ALOAD);
		ctx.emit(headSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
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
