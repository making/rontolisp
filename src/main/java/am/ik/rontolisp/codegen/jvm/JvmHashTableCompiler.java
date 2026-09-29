package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.rontolisp.compiler.OperandTypes;
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
		emitTableCheck(ctx, lispName);
		JvmJavaSites javaSites = ctx.javaSites;
		if (javaSites == null) {
			return;
		}
		// The operator the refusal names, or null (ACONST_NULL) for an unnamed one.
		String reported = OperandTypes.reportedOperator(lispName);
		if (reported == null) {
			ctx.body.aconst_null();
		}
		else {
			JvmEmitHelper.compileUnspelledLiteral(reported, ctx);
		}
		ctx.body.invokestatic(javaSites.direct().tableGuard());
	}

	/**
	 * Runs the table operand on the stack through {@code _ckTab} under the accessor's
	 * wrapper: anything but a hash table is its {@code HASH-TABLE} type-error, named
	 * after the accessor when that is a named operator -- the helpers below cast the
	 * operand, which failed with no datum (a {@code ClassCastException}, or an
	 * {@code IncompatibleClassChangeError} for an interface call on a number).
	 * @param ctx the compilation context, the operand on top of its stack
	 * @param lispName the accessor the report names
	 */
	private static void emitTableCheck(JvmLispCompiler.Ctx ctx, String lispName) {
		@Nullable String outer = ctx.operator;
		ctx.operator = lispName;
		try {
			ctx.body.invokestatic(ctx.numOp(JvmOperandTypeRuntime.CK_TAB).entry());
		}
		finally {
			ctx.operator = outer;
		}
	}

	// The same guard for a table operand with one more argument evaluated above it on the
	// stack (gethash's default, %puthash's value): every argument is evaluated before the
	// table is refused, as the interpreter does.
	private static void emitHostTableGuardUnderOne(JvmLispCompiler.Ctx ctx, String lispName) {
		int top = ctx.allocTemp();
		ctx.body.astore(top);
		emitHostTableGuard(ctx, lispName);
		ctx.body.aload(top);
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
			MethodCode.Label notEqualp = ctx.body.newLabel();
			MethodCode.Label end = ctx.body.newLabel();
			ctx.body.ifnull(notEqualp);
			JvmEmitHelper.compileStringLiteral(LispNames.EQUALP, ctx);
			ctx.body.goto_(end);
			ctx.body.labelBinding(notEqualp);
			JvmEmitHelper.compileStringLiteral(LispNames.EQUAL, ctx);
			ctx.body.labelBinding(end);
			return;
		}
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		emitHostTableGuard(ctx, LispNames.HASH_TABLE_TEST);
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.TEST, JvmHashRuntimeBuilder.TEST_DESC);
		// The test code goes into a temp: each comparison below consumes its own copy
		// (3 eq, 2 eql, 1 equalp, else equal).
		MethodCode c = ctx.body;
		int testSlot = ctx.allocTemp();
		MethodCode.Label eql = c.newLabel();
		MethodCode.Label eq = c.newLabel();
		MethodCode.Label equalp = c.newLabel();
		MethodCode.Label end = c.newLabel();
		c.istore(testSlot);
		c.iload(testSlot).loadConstant(2).if_icmpeq(eql);
		c.iload(testSlot).loadConstant(3).if_icmpeq(eq);
		c.iload(testSlot).loadConstant(1).if_icmpeq(equalp);
		JvmEmitHelper.compileStringLiteral(LispNames.EQUAL, ctx);
		c.goto_(end);
		c.labelBinding(eql);
		JvmEmitHelper.compileStringLiteral(LispNames.EQL, ctx);
		c.goto_(end);
		c.labelBinding(eq);
		JvmEmitHelper.compileStringLiteral(LispNames.EQ_GENERAL, ctx);
		c.goto_(end);
		c.labelBinding(equalp);
		JvmEmitHelper.compileStringLiteral(LispNames.EQUALP, ctx);
		c.labelBinding(end);
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
	// (progn table constant): compiled as that expansion, except that the table is
	// checked in between -- anything but a hash table is its type-error, and a java:
	// program refuses a host map too.
	private static void compileTableThenConstant(LispCons cons, JvmLispCompiler.Ctx ctx, String className,
			String lispName, LispVal expansion) {
		List<LispVal> forms = ((LispCons) expansion).toList();
		JvmExprCompiler.compileExpr(forms.get(1), ctx, className);
		emitHostTableGuard(ctx, lispName);
		ctx.body.pop();
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
			ctx.body.aconst_null();
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
		MethodCode c = ctx.body;

		// func = args[1]; pairs = _hashValues(args[2])
		JvmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx, className);
		int funcSlot = ctx.allocTemp();
		c.astore(funcSlot);

		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		emitHostTableGuard(ctx, LispNames.MAPHASH);
		invokeHelper(ctx, className, JvmHashRuntimeBuilder.VALUES, JvmHashRuntimeBuilder.VALUES_DESC);
		int arrSlot = ctx.allocTemp();
		c.astore(arrSlot);

		// len = arr.length
		int lenSlot = ctx.allocTemp();
		c.aload(arrSlot).arraylength().istore(lenSlot);

		// i = 0
		int iSlot = ctx.allocTemp();
		c.iconst_0().istore(iSlot);

		int pairSlot = ctx.allocTemp();

		// loop: if i >= len goto end
		MethodCode.Label loop = c.newBoundLabel();
		MethodCode.Label end = c.newLabel();
		c.iload(iSlot).iload(lenSlot).if_icmpge(end);

		// pair = (Object[]) arr[i]
		c.aload(arrSlot).iload(iSlot).aaload().checkcast(ctx.objectArrayClass.entry()).astore(pairSlot);

		// _invoke_2(func, pair[0], pair[1]); pop
		c.aload(funcSlot);
		c.aload(pairSlot).iconst_0().aaload();
		c.aload(pairSlot).iconst_1().aaload();
		JvmFunctionCallCompiler.emitDispatchCall(2, ctx, className);
		c.pop();

		// i++, and again
		c.iinc(iSlot, 1).goto_(loop);

		// end: maphash returns nil
		c.labelBinding(end);
		c.aconst_null();
	}

	private static void invokeHelper(JvmLispCompiler.Ctx ctx, String className, String name, String desc) {
		ctx.body.invokestatic(ctx.cp.methodRef(className, name, desc));
	}

}
