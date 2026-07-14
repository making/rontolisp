package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the internal {@code %block} return boundary that the loop macros
 * ({@code do}/{@code dolist}/{@code dotimes}) wrap their expansion in. The body runs as
 * an implicit {@code progn}; its value is stored into a dedicated local and the block
 * evaluates to that local. A {@code return} form (see {@link JvmReturnCompiler}) stores
 * its value into the same local and jumps straight to the block's exit, skipping the rest
 * of the body and any result form.
 *
 * <p>
 * Storing the value into a local and jumping (rather than leaving it on the operand
 * stack) means the exit is reached with the operand stack the block was entered with --
 * recorded here as {@code entryStack} -- on every path, which the version-50
 * type-inference verifier requires. A {@code return} mid-expression discards whatever the
 * body had pushed on top of that (see {@link JvmReturnCompiler}).
 */
final class JvmBlockCompiler {

	private JvmBlockCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		int savedNextLocal = ctx.nextLocal;
		int rvSlot = ctx.allocTemp();
		ctx.blockTargets.push(new JvmLispCompiler.BlockTarget(rvSlot, new ArrayList<>(), ctx.stack.snapshot()));
		// Body forms run as a progn, leaving the last value on the stack.
		if (parts.size() <= 1) {
			ctx.emit(Opcode.ACONST_NULL);
		}
		else {
			for (int i = 1; i < parts.size(); i++) {
				if (i > 1) {
					ctx.emit(Opcode.POP);
				}
				JvmExprCompiler.compileExpr(parts.get(i), ctx, className);
			}
		}
		// Normal completion: store the body value into the block's slot.
		ctx.emit(Opcode.ASTORE);
		ctx.emit(rvSlot);
		JvmLispCompiler.BlockTarget target = ctx.blockTargets.pop();
		int exit = ctx.code.size();
		for (int patchPos : target.exitPatches()) {
			JvmEmitHelper.patchBranch(ctx, patchPos, exit);
		}
		// The block's value is the slot, populated by either normal completion or return.
		ctx.emit(Opcode.ALOAD);
		ctx.emit(rvSlot);
		ctx.nextLocal = savedNextLocal;
	}

}
