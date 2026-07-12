package am.ik.rontolisp.codegen.jvm;

import java.util.List;
import java.util.Objects;

import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the internal {@code (%signal-cond condition message)} primitive behind
 * {@code signal}: when a {@code handler-case} handler is established on the current
 * thread of control (the per-thread depth counter is positive), the condition is raised
 * through the {@code _condTl} channel like {@code %error-cond}; otherwise the whole form
 * yields nil (the CL fall-through of an unhandled signal). The arguments are evaluated
 * either way.
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
		// A handler exists: raise like %error-cond (set the condition channel, throw).
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
		ctx.emit(Opcode.ACONST_NULL);
		ctx.nextLocal = savedNextLocal;
	}

}
