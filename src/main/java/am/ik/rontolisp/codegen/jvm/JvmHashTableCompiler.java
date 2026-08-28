package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.rontolisp.macro.LispMacroExpander;

/**
 * Compiles the hash-table built-ins. The simple operations push their arguments and call
 * the matching static runtime helper emitted by {@link JvmHashRuntimeBuilder};
 * {@code maphash} is compiled inline as a loop over the helper-produced value array,
 * dispatching the function with the shared call mechanism.
 */
final class JvmHashTableCompiler {

	private JvmHashTableCompiler() {
	}

	static void compileMake(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// The arguments are read from the SOURCE, never evaluated: :test 'equalp marks
		// the table so its keys are folded before they are placed, and every other
		// keyword (:size and friends) is accepted and ignored.
		if (ctx.usesEqualpHashTables && LispMacroExpander.isEqualpHashTableMake(cons)) {
			invokeHelper(ctx, className, JvmHashRuntimeBuilder.MAKE_EQUALP, JvmHashRuntimeBuilder.MAKE_EQUALP_DESC);
			return;
		}
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.MAKE, JvmHashRuntimeBuilder.MAKE_DESC);
	}

	/**
	 * Compiles {@code hash-table-test} to the test the table actually implements:
	 * {@code equalp} when it folds its keys, {@code equal} otherwise -- an {@code eql}
	 * table still places structurally ({@code .todo/012}). A program that can build no
	 * folding table answers the constant, which is then the only true answer.
	 * @param cons the accessor expression
	 * @param ctx the compilation context
	 * @param className the generated class
	 */
	static void compileTest(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		if (!ctx.usesEqualpHashTables) {
			JvmExprCompiler.compileExpr(LispMacroExpander.expandHashTableTest(cons), ctx, className);
			return;
		}
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.EQUALP_P, JvmHashRuntimeBuilder.EQUALP_P_DESC);
		int ifNotEqualp = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		JvmEmitHelper.compileStringLiteral(LispNames.EQUALP, ctx);
		int gotoEnd = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNotEqualp, ctx.code.size());
		JvmEmitHelper.compileStringLiteral(LispNames.EQUAL, ctx);
		JvmEmitHelper.patchBranch(ctx, gotoEnd, ctx.code.size());
	}

	static void compileGet(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		if (args.size() > 3) {
			JvmExprCompiler.compileExpr(args.get(3), ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.GET, JvmHashRuntimeBuilder.GET_DESC);
	}

	static void compilePut(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (%puthash key table value)
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmExprCompiler.compileExpr(args.get(3), ctx, className);
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.PUT, JvmHashRuntimeBuilder.PUT_DESC);
	}

	static void compileRem(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.REM, JvmHashRuntimeBuilder.REM_DESC);
	}

	static void compileClr(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.CLR, JvmHashRuntimeBuilder.CLR_DESC);
	}

	static void compileCount(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.COUNT, JvmHashRuntimeBuilder.COUNT_DESC);
	}

	static void compileP(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.P, JvmHashRuntimeBuilder.P_DESC);
	}

	static void compileMaphash(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// The inline walk below is a loop in expression position: its head must sit at
		// operand stack depth 0, or HotSpot refuses to OSR-compile the method
		// (JvmEmitHelper.inLoopScope).
		JvmEmitHelper.inLoopScope(ctx, () -> compileMaphashLoop(cons, ctx, className));
	}

	private static void compileMaphashLoop(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		ctx.indirectCallArities.add(2);

		// func = args[1]; pairs = _hashValues(args[2])
		JvmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx, className);
		int funcSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(funcSlot);

		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.VALUES, JvmHashRuntimeBuilder.VALUES_DESC);
		int arrSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(arrSlot);

		// len = arr.length
		int lenSlot = ctx.allocTemp();
		ctx.emit(Opcode.ALOAD);
		ctx.emit(arrSlot);
		ctx.emit(Opcode.ARRAYLENGTH);
		ctx.emit(Opcode.ISTORE);
		ctx.emit(lenSlot);

		// i = 0
		int iSlot = ctx.allocTemp();
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.ISTORE);
		ctx.emit(iSlot);

		int pairSlot = ctx.allocTemp();

		// loop: if i >= len goto end
		int loopPos = ctx.code.size();
		ctx.emit(Opcode.ILOAD);
		ctx.emit(iSlot);
		ctx.emit(Opcode.ILOAD);
		ctx.emit(lenSlot);
		int ifGePos = ctx.code.size();
		ctx.emit(Opcode.IF_ICMPGE);
		ctx.emitU2(0);

		// pair = (Object[]) arr[i]
		ctx.emit(Opcode.ALOAD);
		ctx.emit(arrSlot);
		ctx.emit(Opcode.ILOAD);
		ctx.emit(iSlot);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ASTORE);
		ctx.emit(pairSlot);

		// _invoke_2(func, pair[0], pair[1]); pop
		ctx.emit(Opcode.ALOAD);
		ctx.emit(funcSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(pairSlot);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(pairSlot);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
		JvmFunctionCallCompiler.emitDispatchCall(2, ctx, className);
		ctx.emit(Opcode.POP);

		// i++
		ctx.emit(Opcode.IINC);
		ctx.emit(iSlot);
		ctx.emit(1);

		// goto loop
		int gotoPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2((loopPos - gotoPos) & 0xFFFF);

		// end: maphash returns nil
		JvmEmitHelper.patchBranch(ctx, ifGePos, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
	}

	private static void invokeHelper(JvmLispCompiler.Ctx ctx, String className, String name, String desc) {
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8(name), ctx.cp.addUtf8(desc)));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

}
