package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code mapcar} built-in function. Generates an inline loop that applies a
 * function to the parallel elements of one or more lists, building a new list using the
 * sentinel/tail-mutation pattern. With multiple lists the loop stops at the shortest list
 * (Common Lisp semantics).
 */
final class JvmMapcarCompiler {

	private JvmMapcarCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int nLists = args.size() - 2;
		ctx.indirectCallArities.add(nLists);

		// Compile function expression
		JvmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx, className);
		int funcSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(funcSlot);

		// Compile each list expression, guarding it is a list.
		List<Integer> listSlots = new ArrayList<>();
		for (int i = 0; i < nLists; i++) {
			JvmExprCompiler.compileExpr(args.get(2 + i), ctx, className);
			int listSlot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(listSlot);
			JvmEmitHelper.emitRequireListGuard(ctx, listSlot,
					"MAPCAR: argument is not a list (use map for strings/vectors)");
			listSlots.add(listSlot);
		}

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
		// if any list == null, goto exit (stop at the shortest list)
		List<Integer> exitBranches = new ArrayList<>();
		for (int listSlot : listSlots) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(listSlot);
			exitBranches.add(ctx.code.size());
			ctx.emit(Opcode.IFNULL);
			ctx.emitU2(0);
		}

		// Push func and car of each list for the dispatch call
		ctx.emit(Opcode.ALOAD);
		ctx.emit(funcSlot);
		for (int listSlot : listSlots) {
			// car(list) = ((Object[]) list)[0]
			ctx.emit(Opcode.ALOAD);
			ctx.emit(listSlot);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.AALOAD);
		}
		// Call _invoke_<nLists>(func, car...)
		JvmFunctionCallCompiler.emitDispatchCall(nLists, ctx, className);

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
		ctx.emit(Opcode.ALOAD);
		ctx.emit(headSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
	}

}
