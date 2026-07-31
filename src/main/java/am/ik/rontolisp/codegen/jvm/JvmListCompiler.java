package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.ArgumentOrder;
import am.ik.jvm.Opcode;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the {@code list} built-in function.
 *
 * <p>
 * The cons chain has to be linked from the LAST element backwards, but Common Lisp
 * evaluates the argument forms left to right. The two orders are reconciled by evaluating
 * every effectful argument into a temp slot first, in source order, and linking the chain
 * from those temps ({@link ArgumentOrder}; {@code .kb/argument-evaluation-order.md}). A
 * constant argument needs no temp, so an all-literal {@code list} emits what it always
 * did.
 */
final class JvmListCompiler {

	private JvmListCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		// Left-to-right pre-evaluation of every effectful argument into a temp slot.
		List<@Nullable Integer> slots = new ArrayList<>(args.size());
		slots.add(null);
		for (int i = 1; i < args.size(); i++) {
			if (ArgumentOrder.isOrderIndependent(args.get(i))) {
				slots.add(null);
				continue;
			}
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			int slot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slot);
			slots.add(slot);
		}
		// One reused accumulator slot holds the tail while the next cons cell is built.
		int tailSlot = ctx.allocTemp();
		ctx.emit(Opcode.ACONST_NULL);
		for (int i = args.size() - 1; i >= 1; i--) {
			ctx.emit(Opcode.ASTORE);
			ctx.emit(tailSlot);
			ctx.emit(Opcode.ICONST_2);
			ctx.emit(Opcode.ANEWARRAY);
			ctx.emitU2(ctx.objectClass.index());
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_0);
			Integer pre = slots.get(i);
			if (pre == null) {
				JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			}
			else {
				ctx.emit(Opcode.ALOAD);
				ctx.emit(pre);
			}
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tailSlot);
			ctx.emit(Opcode.AASTORE);
		}
	}

}
