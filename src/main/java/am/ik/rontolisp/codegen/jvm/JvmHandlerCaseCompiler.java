package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import am.ik.jvm.ByteCodeWriter;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles {@code (handler-case expr (type ([var]) body...)... [(:no-error ([var])
 * body...)])}: the expression runs inside a catch-any exception-table region (the
 * unwind-protect machinery); the handler reads the typed condition from the per-thread
 * {@code _condTl} channel (set by {@code %error-cond}), synthesizes a
 * {@code simple-error} instance from the exception message when the channel is empty (a
 * plain {@code %error} or a raw runtime exception), dispatches it through the clauses'
 * type tests -- ordinary compiled Lisp forms over a pseudo-local holding the condition --
 * and rethrows when none matches. The per-thread handler depth is incremented around the
 * protected region so {@code signal} raises only under an established handler; a
 * {@code return} exiting the region decrements it through the {@code UnwindScope} cleanup
 * channel ({@code %hc-depth-dec}).
 */
final class JvmHandlerCaseCompiler {

	private JvmHandlerCaseCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException(LispNames.HANDLER_CASE + " expects an expression");
		}
		List<List<LispVal>> errorClauses = new ArrayList<>();
		List<LispVal> noErrorClause = null;
		for (int i = 2; i < parts.size(); i++) {
			if (!(parts.get(i) instanceof LispCons clause) || !(clause.cdr() instanceof LispCons)) {
				throw new IllegalArgumentException(
						LispNames.HANDLER_CASE + " expects (type (var) body...) clauses: " + parts.get(i).print());
			}
			List<LispVal> clauseParts = clause.toList();
			if (clause.car() instanceof LispSymbol head && LispNames.keywordMatches(head.name(), ":no-error")) {
				noErrorClause = clauseParts;
			}
			else {
				errorClauses.add(clauseParts);
			}
		}
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensure(ctx.cp, className);
		int savedNextLocal = ctx.nextLocal;
		// Entering the handler discards the operand stack, so the values the enclosing
		// form had already evaluated are saved into locals and reloaded past the merge:
		// both edges into it then arrive with the same (empty) stack. A handler-case
		// compiled as a statement spills nothing and is byte-identical to before.
		JvmLispCompiler.Ctx.Spill spill = ctx.spillOperandStack();
		int resultSlot = ctx.allocTemp();
		int excSlot = ctx.allocTemp();
		int condSlot = ctx.allocTemp();
		if (!spill.live().isEmpty()) {
			// A return escaping the form cannot leave the enclosing block's operands on
			// the stack: they are in the spill now, and JvmReturnCompiler reloads them.
			ctx.spillScopes.push(new JvmLispCompiler.SpillScope(spill, ctx.blockTargets.size()));
		}
		// depth++ so signal raises inside the protected region (incl. called functions).
		emitDepthAdjust(ctx, className, true);
		LispVal depthDecForm = new LispCons(new LispSymbol(LispNames.HC_DEPTH_DEC_INTERNAL), LispNil.INSTANCE);
		JvmLispCompiler.UnwindScope scope = new JvmLispCompiler.UnwindScope(List.of(depthDecForm),
				ctx.blockTargets.size());
		ctx.unwindScopes.push(scope);
		int start = ctx.code.size();
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		int end = ctx.code.size();
		ctx.unwindScopes.pop();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(resultSlot);
		// Normal completion: depth--, then the :no-error clause (outside the protected
		// region -- an error signaled by it is not caught by this handler-case).
		emitDepthAdjust(ctx, className, false);
		if (noErrorClause != null) {
			compileClauseBody(noErrorClause, resultSlot, resultSlot, ctx, className);
		}
		int gotoDonePos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		// Handler: depth--, read (and clear) the condition channel, synthesize a
		// simple-error from the message when it is empty, dispatch through the clauses.
		int handler = ctx.code.size();
		ctx.stack.enterHandler();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(excSlot);
		emitDepthAdjust(ctx, className, false);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.condTlField).index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlGet).index());
		ctx.emit(Opcode.ASTORE);
		ctx.emit(condSlot);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.condTlField).index());
		ctx.emit(Opcode.ACONST_NULL);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlSet).index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(condSlot);
		int ifHaveCondPos = ctx.code.size();
		ctx.emit(Opcode.IFNONNULL);
		ctx.emitU2(0);
		emitSynthesizeSimpleError(excSlot, condSlot, ctx, className);
		JvmEmitHelper.patchBranch(ctx, ifHaveCondPos, ctx.code.size());
		// Dispatch: the condition rides a pseudo-local so the type tests and clause
		// bodies compile as ordinary Lisp forms.
		String condVarName = "__hc_cond$" + condSlot;
		LispSymbol condVarSym = new LispSymbol(condVarName);
		ctx.locals.put(condVarName, condSlot);
		List<Integer> donePatches = new ArrayList<>();
		try {
			for (List<LispVal> clauseParts : errorClauses) {
				LispVal test = LispMacroExpander.makeHandlerTypeTest(condVarSym, clauseParts.get(0), ctx.closRegistry);
				JvmExprCompiler.compileExpr(test, ctx, className);
				int ifNoMatchPos = ctx.code.size();
				ctx.emit(Opcode.IFNULL);
				ctx.emitU2(0);
				compileClauseBody(clauseParts, condSlot, resultSlot, ctx, className);
				donePatches.add(ctx.code.size());
				ctx.emit(Opcode.GOTO);
				ctx.emitU2(0);
				JvmEmitHelper.patchBranch(ctx, ifNoMatchPos, ctx.code.size());
			}
		}
		finally {
			ctx.locals.remove(condVarName);
		}
		// No clause matched: restore the condition into the channel (an outer
		// handler-case must see the typed instance, not a re-synthesized
		// simple-error) and rethrow.
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.condTlField).index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(condSlot);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlSet).index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(excSlot);
		ctx.emit(Opcode.ATHROW);
		int done = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, gotoDonePos, done);
		for (int patch : donePatches) {
			JvmEmitHelper.patchBranch(ctx, patch, done);
		}
		if (!spill.live().isEmpty()) {
			ctx.spillScopes.pop();
			spill.restore(ctx);
		}
		ctx.emit(Opcode.ALOAD);
		ctx.emit(resultSlot);
		addExceptionEntries(ctx, scope, start, end, handler);
		ctx.nextLocal = savedNextLocal;
	}

	/**
	 * Compiles a clause body -- {@code (type ([var]) body...)} or {@code (:no-error
	 * ([var]) body...)} -- binding the optional variable to the value in
	 * {@code valueSlot} through a pseudo-local, and stores the body's value into
	 * {@code resultSlot}.
	 */
	private static void compileClauseBody(List<LispVal> clauseParts, int valueSlot, int resultSlot,
			JvmLispCompiler.Ctx ctx, String className) {
		String varName = null;
		Integer shadowedSlot = null;
		if (clauseParts.get(1) instanceof LispCons varList && varList.car() instanceof LispSymbol var) {
			varName = var.name();
			shadowedSlot = ctx.locals.put(varName, valueSlot);
		}
		try {
			if (clauseParts.size() <= 2) {
				ctx.emit(Opcode.ACONST_NULL);
			}
			else {
				for (int i = 2; i < clauseParts.size(); i++) {
					if (i > 2) {
						ctx.emit(Opcode.POP);
					}
					JvmExprCompiler.compileExpr(clauseParts.get(i), ctx, className);
				}
			}
			ctx.emit(Opcode.ASTORE);
			ctx.emit(resultSlot);
		}
		finally {
			if (varName != null) {
				if (shadowedSlot != null) {
					ctx.locals.put(varName, shadowedSlot);
				}
				else {
					ctx.locals.remove(varName);
				}
			}
		}
	}

	/**
	 * Synthesizes the {@code simple-error} instance of a condition-less throw:
	 * {@code (list '%class-simple-error "<quote-framed message>" nil)}, with a nil
	 * message slot when the throwable carries none.
	 */
	private static void emitSynthesizeSimpleError(int excSlot, int condSlot, JvmLispCompiler.Ctx ctx,
			String className) {
		ConstantPool.ClassConstant throwableClass = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/Throwable"));
		ConstantPool.MethodrefConstant getMessage = ctx.cp.addMethodref(throwableClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("getMessage"), ctx.cp.addUtf8("()Ljava/lang/String;")));
		int concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;").index();
		int msgSlot = ctx.allocTemp();
		ctx.emit(Opcode.ALOAD);
		ctx.emit(excSlot);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(getMessage.index());
		ctx.emit(Opcode.DUP);
		int ifNullMsgPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		// "\"" + msg + "\"" -- the quote-framed runtime string representation.
		JvmEmitHelper.compileStringLiteral("\"", ctx);
		ctx.emit(Opcode.SWAP);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat);
		JvmEmitHelper.compileStringLiteral("\"", ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat);
		int gotoHavePos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNullMsgPos, ctx.code.size());
		ctx.emit(Opcode.POP);
		ctx.emit(Opcode.ACONST_NULL);
		JvmEmitHelper.patchBranch(ctx, gotoHavePos, ctx.code.size());
		ctx.emit(Opcode.ASTORE);
		ctx.emit(msgSlot);
		String msgVarName = "__hc_msg$" + msgSlot;
		ctx.locals.put(msgVarName, msgSlot);
		try {
			// (list '%class-simple-error __hc_msg nil)
			LispVal quotedTag = new LispCons(new LispSymbol(LispNames.QUOTE),
					new LispCons(new LispSymbol("%class-SIMPLE-ERROR"), LispNil.INSTANCE));
			LispVal instance = new LispCons(new LispSymbol(LispNames.LIST), new LispCons(quotedTag,
					new LispCons(new LispSymbol(msgVarName), new LispCons(LispNil.INSTANCE, LispNil.INSTANCE))));
			JvmExprCompiler.compileExpr(instance, ctx, className);
		}
		finally {
			ctx.locals.remove(msgVarName);
		}
		ctx.emit(Opcode.ASTORE);
		ctx.emit(condSlot);
	}

	/**
	 * Emits an increment ({@code up}) or decrement of the per-thread handler-depth
	 * counter: {@code _hcDepthTl.set(Integer.valueOf(read() +/- 1))}, where a null value
	 * reads as 0.
	 */
	static void emitDepthAdjust(JvmLispCompiler.Ctx ctx, String className, boolean up) {
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensure(ctx.cp, className);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.depthTlField).index());
		emitReadDepth(ctx, className);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(up ? Opcode.IADD : Opcode.ISUB);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.integerValueOf.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlSet).index());
	}

	/**
	 * Emits the read of the per-thread handler depth as an {@code int} on the operand
	 * stack (null = 0).
	 */
	static void emitReadDepth(JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensure(ctx.cp, className);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.depthTlField).index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlGet).index());
		ctx.emit(Opcode.DUP);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.integerClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.integerValue.index());
		int gotoHavePos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
		ctx.emit(Opcode.POP);
		ctx.emit(Opcode.ICONST_0);
		JvmEmitHelper.patchBranch(ctx, gotoHavePos, ctx.code.size());
	}

	/**
	 * Emits the {@code %hc-depth-dec} internal form: decrements the handler depth and
	 * yields nil (the {@code UnwindScope} cleanup channel pops the value).
	 */
	static void compileDepthDec(JvmLispCompiler.Ctx ctx, String className) {
		emitDepthAdjust(ctx, className, false);
		ctx.emit(Opcode.ACONST_NULL);
	}

	/**
	 * Appends the catch-any exception-table entries of the protected region, excluding
	 * the recorded return-site cleanup holes (same sweep as
	 * {@code JvmUnwindProtectCompiler}).
	 */
	private static void addExceptionEntries(JvmLispCompiler.Ctx ctx, JvmLispCompiler.UnwindScope scope, int start,
			int end, int handler) {
		int cur = start;
		for (int[] hole : scope.holes) {
			if (hole[0] > cur) {
				ctx.exceptionTable.add(new ByteCodeWriter.ExceptionTableEntry(cur, hole[0], handler, 0));
			}
			cur = Math.max(cur, hole[1]);
		}
		if (cur < end) {
			ctx.exceptionTable.add(new ByteCodeWriter.ExceptionTableEntry(cur, end, handler, 0));
		}
	}

}
