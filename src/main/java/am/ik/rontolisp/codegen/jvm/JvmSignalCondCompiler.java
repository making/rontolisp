package am.ik.rontolisp.codegen.jvm;

import java.util.List;
import java.util.Objects;

import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the internal {@code (%signal-cond condition message)} primitive behind
 * {@code signal}: when a {@code handler-case} that will HANDLE the condition is
 * established on the current thread of control, the condition is raised through the
 * {@code _condTl} channel like {@code %error-cond}; otherwise the whole form yields nil
 * (the CL fall-through of an unhandled signal, CLHS 9.1.4.1). The arguments are evaluated
 * either way.
 *
 * <p>
 * "Will handle" is decided in two steps. The per-thread depth counter must be positive (a
 * handler-case is armed on THIS thread -- the cluster stack below is a shared global, so
 * the depth read keeps another thread's handler-case from capturing this thread's
 * signal), and, under {@code Ctx.signalClauseMatch}, the injected {@code %hc-match-p}
 * defun must find a MATCHING handler-case clause on the dynamic
 * {@code %handler-clusters%} stack -- a handler-case whose clauses do not match is not an
 * applicable handler and is declined. Outside that gate (the program signals but
 * establishes no handler-case, or vice versa) the depth test alone decides, exactly as
 * before, and the emission is byte-identical.
 */
final class JvmSignalCondCompiler {

	private JvmSignalCondCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensure(ctx.cp, className);
		int savedNextLocal = ctx.nextLocal;
		int condSlot = ctx.allocTemp();
		int msgSlot = ctx.allocTemp();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(condSlot);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(msgSlot);
		JvmHandlerCaseCompiler.emitReadDepth(ctx, className);
		int ifUnhandledPos = ctx.code.size();
		ctx.emit(Opcode.IFLE);
		ctx.emitU2(0);
		int ifNoMatchPos = -1;
		if (ctx.signalClauseMatch) {
			// Ask the clause-type stack whether any armed handler-case clause matches;
			// the condition rides a pseudo-local so the call compiles as ordinary Lisp.
			String condVarName = "__sc_cond$" + condSlot;
			Integer shadowed = ctx.locals.put(condVarName, condSlot);
			try {
				JvmExprCompiler.compileExpr(new LispCons(new LispSymbol(LispNames.HC_MATCH_INTERNAL),
						new LispCons(new LispSymbol(condVarName), LispNil.INSTANCE)), ctx, className);
			}
			finally {
				if (shadowed != null) {
					ctx.locals.put(condVarName, shadowed);
				}
				else {
					ctx.locals.remove(condVarName);
				}
			}
			ifNoMatchPos = ctx.code.size();
			ctx.emit(Opcode.IFNULL);
			ctx.emitU2(0);
		}
		// A handler that will handle the condition exists: raise like %error-cond (set
		// the condition channel, throw).
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.condTlField).index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(condSlot);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlSet).index());
		String msgVarName = "__signal_msg$" + msgSlot;
		Integer shadowed = ctx.locals.put(msgVarName, msgSlot);
		try {
			JvmErrorCompiler.compileThrowRuntimeException(new LispSymbol(msgVarName), ctx, className);
		}
		finally {
			if (shadowed != null) {
				ctx.locals.put(msgVarName, shadowed);
			}
			else {
				ctx.locals.remove(msgVarName);
			}
		}
		JvmEmitHelper.patchBranch(ctx, ifUnhandledPos, ctx.code.size());
		if (ifNoMatchPos >= 0) {
			JvmEmitHelper.patchBranch(ctx, ifNoMatchPos, ctx.code.size());
		}
		ctx.emit(Opcode.ACONST_NULL);
		ctx.nextLocal = savedNextLocal;
	}

}
