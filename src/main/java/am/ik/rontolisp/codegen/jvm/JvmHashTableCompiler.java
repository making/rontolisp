package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispHashTable;
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

	/**
	 * Runs the table operand on the stack through the program's {@code _jcktab} in a
	 * {@code java:} program: a host {@code LinkedHashMap} a call answered is no Lisp
	 * table, and the helpers read their operand by the class alone -- a lookup in one
	 * answered nil, a store wrote a bucket into it. It is refused here with the
	 * interpreter's {@code OP expects a hash table, got X}. A program without
	 * {@code java:} holds no host map and emits nothing.
	 * @param ctx the compilation context, the operand on top of its stack
	 * @param lispName the operator the refusal names
	 */
	private static void emitHostTableGuard(JvmLispCompiler.Ctx ctx, String lispName) {
		JvmJavaSites javaSites = ctx.javaSites;
		if (javaSites == null) {
			return;
		}
		JvmEmitHelper.compileUnspelledLiteral(lispName, ctx);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(javaSites.direct().tableGuard().index());
	}

	// The same guard for a table operand with one more argument evaluated above it on the
	// stack (gethash's default, %puthash's value): every argument is evaluated before the
	// table is refused, as the interpreter does.
	private static void emitHostTableGuardUnderOne(JvmLispCompiler.Ctx ctx, String lispName) {
		if (ctx.javaSites == null) {
			return;
		}
		int top = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(top);
		emitHostTableGuard(ctx, lispName);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(top);
	}

	static void compileMake(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// The arguments are read from the SOURCE, never evaluated: a literal :test
		// marks the table so its lookups compare and hash by that test, and every
		// other keyword (:size and friends) is accepted and ignored.
		if (ctx.usesEqualpHashTables && LispMacroExpander.isEqualpHashTableMake(cons)) {
			invokeHelper(ctx, className, JvmHashRuntimeBuilder.MAKE_EQUALP, JvmHashRuntimeBuilder.MAKE_EQUALP_DESC);
			return;
		}
		if (ctx.usesIdentityHashTables) {
			int testCode = LispMacroExpander.hashTableTestCode(cons);
			if (testCode == LispHashTable.TEST_EQ) {
				invokeHelper(ctx, className, JvmHashRuntimeBuilder.MAKE_EQ, JvmHashRuntimeBuilder.MAKE_EQ_DESC);
				return;
			}
			if (testCode == LispHashTable.TEST_EQL) {
				invokeHelper(ctx, className, JvmHashRuntimeBuilder.MAKE_EQL, JvmHashRuntimeBuilder.MAKE_EQL_DESC);
				return;
			}
		}
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.MAKE, JvmHashRuntimeBuilder.MAKE_DESC);
	}

	/**
	 * Compiles {@code hash-table-test} to the test the table actually implements. A
	 * program that can build no folding or identity table answers the constant, which is
	 * then the only true answer.
	 * @param cons the accessor expression
	 * @param ctx the compilation context
	 * @param className the generated class
	 */
	static void compileTest(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		if (!ctx.usesEqualpHashTables && !ctx.usesIdentityHashTables) {
			compileTableThenConstant(cons, ctx, className, LispNames.HASH_TABLE_TEST,
					LispMacroExpander.expandHashTableTest(cons));
			return;
		}
		if (!ctx.usesIdentityHashTables) {
			List<LispVal> args = cons.toList();
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			emitHostTableGuard(ctx, LispNames.HASH_TABLE_TEST);
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
			return;
		}
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		emitHostTableGuard(ctx, LispNames.HASH_TABLE_TEST);
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.TEST, JvmHashRuntimeBuilder.TEST_DESC);
		// The test code goes into a temp: each comparison below consumes its own copy
		// (3 eq, 2 eql, 1 equalp, else equal).
		int testSlot = ctx.allocTemp();
		ctx.emit(Opcode.ISTORE);
		ctx.emit(testSlot);
		ctx.emit(Opcode.ILOAD);
		ctx.emit(testSlot);
		ctx.emit(Opcode.ICONST_2);
		int ifEql = ctx.code.size();
		ctx.emit(Opcode.IF_ICMPEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ILOAD);
		ctx.emit(testSlot);
		ctx.emit(Opcode.ICONST_3);
		int ifEq = ctx.code.size();
		ctx.emit(Opcode.IF_ICMPEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ILOAD);
		ctx.emit(testSlot);
		ctx.emit(Opcode.ICONST_1);
		int ifEqualp = ctx.code.size();
		ctx.emit(Opcode.IF_ICMPEQ);
		ctx.emitU2(0);
		JvmEmitHelper.compileStringLiteral(LispNames.EQUAL, ctx);
		int gotoEnd = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifEql, ctx.code.size());
		JvmEmitHelper.compileStringLiteral(LispNames.EQL, ctx);
		int gotoEnd2 = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifEq, ctx.code.size());
		JvmEmitHelper.compileStringLiteral(LispNames.EQ_GENERAL, ctx);
		int gotoEnd3 = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifEqualp, ctx.code.size());
		JvmEmitHelper.compileStringLiteral(LispNames.EQUALP, ctx);
		JvmEmitHelper.patchBranch(ctx, gotoEnd, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, gotoEnd2, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, gotoEnd3, ctx.code.size());
	}

	/**
	 * Compiles {@code hash-table-rehash-size} / {@code hash-table-rehash-threshold}: the
	 * table has no growth knobs of its own, so the standard default is reported after
	 * evaluating the argument.
	 * @param cons the accessor expression
	 * @param ctx the compilation context
	 * @param className the generated class
	 * @param lispName the accessor
	 * @param value the reported default
	 */
	static void compileGrowthConstant(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String lispName,
			double value) {
		compileTableThenConstant(cons, ctx, className, lispName,
				LispMacroExpander.expandHashTableGrowthConstant(cons, value));
	}

	// An accessor that answers a constant after evaluating its table, expanded to
	// (progn table constant): compiled as that expansion, except that a java: program
	// refuses a host map in between.
	private static void compileTableThenConstant(LispCons cons, JvmLispCompiler.Ctx ctx, String className,
			String lispName, LispVal expansion) {
		if (ctx.javaSites == null) {
			JvmExprCompiler.compileExpr(expansion, ctx, className);
			return;
		}
		List<LispVal> forms = ((LispCons) expansion).toList();
		JvmExprCompiler.compileExpr(forms.get(1), ctx, className);
		emitHostTableGuard(ctx, lispName);
		ctx.emit(Opcode.POP);
		JvmExprCompiler.compileExpr(forms.get(2), ctx, className);
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
		emitHostTableGuardUnderOne(ctx, LispNames.GETHASH);
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.GET, JvmHashRuntimeBuilder.GET_DESC);
	}

	static void compilePut(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// (%puthash key table value)
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmExprCompiler.compileExpr(args.get(3), ctx, className);
		emitHostTableGuardUnderOne(ctx, LispNames.PUTHASH);
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.PUT, JvmHashRuntimeBuilder.PUT_DESC);
	}

	static void compileRem(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		emitHostTableGuard(ctx, LispNames.REMHASH);
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.REM, JvmHashRuntimeBuilder.REM_DESC);
	}

	static void compileClr(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		emitHostTableGuard(ctx, LispNames.CLRHASH);
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.CLR, JvmHashRuntimeBuilder.CLR_DESC);
	}

	static void compileCount(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String lispName) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		emitHostTableGuard(ctx, lispName);
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
		emitHostTableGuard(ctx, LispNames.MAPHASH);
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
