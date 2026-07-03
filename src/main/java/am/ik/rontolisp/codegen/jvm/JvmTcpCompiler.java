package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

import org.jspecify.annotations.Nullable;

/**
 * Compiles the {@code rontolisp:tcp-connect} / {@code tcp-listen} / {@code tcp-accept} /
 * {@code tcp-local-port} built-ins. Each evaluates its arguments and calls the matching
 * {@code _tcp*} runtime helper emitted by {@link JvmSocketRuntimeBuilder}; the returned
 * value is a {@code Long} stream handle indexing the shared {@code _streams} table, so
 * the standard stream built-ins ({@code read-line}, {@code write-line},
 * {@code read-byte}, {@code write-byte}, {@code close}) work on it directly.
 */
final class JvmTcpCompiler {

	private JvmTcpCompiler() {
	}

	static void compile(String member, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		switch (member) {
			case LispNames.TCP_CONNECT -> {
				requireArgs(member, args, 2, 2);
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				invoke(ctx, ctx.tcpConnectHelper, member);
			}
			case LispNames.TCP_LISTEN -> {
				requireArgs(member, args, 1, 2);
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				if (args.size() == 3) {
					JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				}
				else {
					ctx.emit(Opcode.ACONST_NULL);
				}
				invoke(ctx, ctx.tcpListenHelper, member);
			}
			case LispNames.TCP_ACCEPT -> {
				requireArgs(member, args, 1, 1);
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				invoke(ctx, ctx.tcpAcceptHelper, member);
			}
			case LispNames.TCP_LOCAL_PORT -> {
				requireArgs(member, args, 1, 1);
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				invoke(ctx, ctx.tcpLocalPortHelper, member);
			}
			default -> throw new UnsupportedOperationException("Unknown tcp built-in: " + member);
		}
	}

	private static void requireArgs(String member, List<LispVal> args, int min, int max) {
		int given = args.size() - 1;
		if (given < min || given > max) {
			String expected = (min == max) ? String.valueOf(min) : min + " or " + max;
			throw new UnsupportedOperationException(member + " expects " + expected + " arguments, got " + given);
		}
	}

	private static void invoke(JvmLispCompiler.Ctx ctx, @Nullable MethodrefConstant helper, String member) {
		if (helper == null) {
			throw new IllegalStateException(member + " helper method was not emitted");
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(helper.index());
	}

}
