package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.Opcode;

/**
 * The argument sequence a compiled callee takes, emitted where a call's argument count is
 * known: its required parameters, then its PHYSICAL optionals -- each an argument or the
 * UNSUPPLIED marker ({@link JvmUnsupplied}) -- then, for a variadic callee, the rest list
 * of whatever is past them (nil when nothing is). An optional argument therefore travels
 * as a parameter and conses nothing; only a surplus past the last physical optional is
 * linked into a list ({@code LambdaLists.toNative}). The dispatchers
 * ({@code JvmRuntimeBuilder.renderCase}) and the spread walk ({@code renderSpreadCase},
 * {@code JvmApplyCompiler}) build the same sequence out of their own sources.
 */
final class JvmPhysicalArgs {

	private JvmPhysicalArgs() {
	}

	/**
	 * Pushes the UNSUPPLIED marker.
	 * @param ctx the compilation context
	 * @param className the class being emitted
	 */
	static void emitUnsupplied(JvmLispCompiler.Ctx ctx, String className) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.unsupplied.ref(ctx.cp, className).index());
	}

	/**
	 * Emits the physical arguments of a call to {@code fi}. Each element of {@code args}
	 * pushes one argument and is run exactly once, left to right.
	 * @param ctx the compilation context
	 * @param className the class being emitted
	 * @param fi the callee
	 * @param args one emitter per argument; a count the callee accepts
	 */
	static void emit(JvmLispCompiler.Ctx ctx, String className, JvmLispCompiler.FunctionInfo fi, List<Runnable> args) {
		emit(ctx, className, fi.required(), fi.optionals(), fi.variadic(), args);
	}

	/**
	 * Emits the physical arguments of a call to a callee of the given shape. Each element
	 * of {@code args} pushes one argument and is run exactly once, left to right: the
	 * ones a parameter takes straight onto the operand stack, a surplus into temps first
	 * and then linked into the rest list, newest link first.
	 * @param ctx the compilation context
	 * @param className the class being emitted
	 * @param required the callee's required parameter count
	 * @param optionals its physical optional count
	 * @param variadic whether it takes a rest list
	 * @param args one emitter per argument; a count the callee accepts
	 */
	static void emit(JvmLispCompiler.Ctx ctx, String className, int required, int optionals, boolean variadic,
			List<Runnable> args) {
		int positional = required + optionals;
		int supplied = args.size();
		if (!variadic || supplied <= positional) {
			args.forEach(Runnable::run);
			for (int i = supplied; i < positional; i++) {
				emitUnsupplied(ctx, className);
			}
			if (variadic) {
				ctx.emit(Opcode.ACONST_NULL);
			}
			return;
		}
		for (int i = 0; i < positional; i++) {
			args.get(i).run();
		}
		List<Integer> extraSlots = new ArrayList<>();
		for (int i = positional; i < supplied; i++) {
			args.get(i).run();
			int slot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slot);
			extraSlots.add(slot);
		}
		int restSlot = ctx.allocTemp();
		ctx.emit(Opcode.ACONST_NULL);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(restSlot);
		for (int k = extraSlots.size() - 1; k >= 0; k--) {
			ctx.emit(Opcode.ICONST_2);
			ctx.emit(Opcode.ANEWARRAY);
			ctx.emitU2(ctx.objectClass.index());
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(extraSlots.get(k));
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(restSlot);
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(restSlot);
		}
		ctx.emit(Opcode.ALOAD);
		ctx.emit(restSlot);
	}

	/**
	 * Emits the physical arguments of a call to {@code fi} read out of the argument LIST
	 * in {@code listSlot}: each required parameter its element, each physical optional
	 * its element or -- past the end of the list -- the marker, and the rest list the
	 * tail past them. Each element is re-walked from the head, so nothing but the list
	 * slot is live. The count has been judged already ({@code _arityChk}); a required
	 * parameter past the end binds nil, like the {@code car}/{@code cdr} it is.
	 * @param ctx the compilation context
	 * @param className the class being emitted
	 * @param fi the callee
	 * @param listSlot the local holding the argument list
	 */
	static void emitFromList(JvmLispCompiler.Ctx ctx, String className, JvmLispCompiler.FunctionInfo fi, int listSlot) {
		int positional = fi.positional();
		for (int i = 0; i < positional; i++) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(listSlot);
			for (int step = 0; step < i; step++) {
				emitNullSafeCell(ctx, 1);
			}
			if (i < fi.required()) {
				emitNullSafeCell(ctx, 0);
			}
			else {
				// cell == null ? UNSUPPLIED : car(cell)
				ctx.emit(Opcode.INVOKESTATIC);
				ctx.emitU2(ctx.unsupplied.optArgRef(ctx.cp, ctx.cp.addClass(ctx.cp.addUtf8(className))).index());
			}
		}
		if (fi.variadic()) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(listSlot);
			for (int step = 0; step < positional; step++) {
				emitNullSafeCell(ctx, 1);
			}
		}
	}

	// Replaces the cons on the stack with its car (field 0) or cdr (field 1); nil
	// passes through, like the car/cdr built-ins.
	private static void emitNullSafeCell(JvmLispCompiler.Ctx ctx, int field) {
		ctx.emit(Opcode.DUP);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(field == 0 ? Opcode.ICONST_0 : Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
		JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
	}

}
