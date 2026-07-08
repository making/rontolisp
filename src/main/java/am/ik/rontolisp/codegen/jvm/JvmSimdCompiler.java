package am.ik.rontolisp.codegen.jvm;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

/**
 * Compiles the accelerated {@code simd:} kernels ({@code add}/{@code sub}/{@code mul}/
 * {@code scale}/{@code dot}/{@code sum}) to calls into the embedded
 * {@link JvmSimdVectorTemplate bridge}, replacing the scalar {@code simd.lisp} reference
 * at those call sites. Only wired in when the {@code --simd} flag emitted the runtime
 * (i.e. {@link JvmLispCompiler.Ctx#simdOps} is non-null); otherwise the qualified call
 * falls through to the ordinary spliced {@code simd:} defun. Each call site first invokes
 * the emitted {@code _simdInit} helper (which lazily defines the bridge, see
 * {@link JvmSimdRuntimeBuilder}), then evaluates the arguments and calls the matching
 * bridge entry point. {@code mean}/{@code norm} are not intercepted directly -- they are
 * accelerated transitively because their spliced bodies call {@code sum}/{@code dot}.
 */
final class JvmSimdCompiler {

	private JvmSimdCompiler() {
	}

	/**
	 * Returns whether the given {@code simd} package member is one of the six
	 * vectorizable kernels this compiler accelerates.
	 */
	static boolean handles(String member) {
		return LispNames.SIMD_ADD.equals(member) || LispNames.SIMD_SUB.equals(member)
				|| LispNames.SIMD_MUL.equals(member) || LispNames.SIMD_SCALE.equals(member)
				|| LispNames.SIMD_DOT.equals(member) || LispNames.SIMD_SUM.equals(member);
	}

	static void compile(String member, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		Map<String, MethodrefConstant> ops = ctx.simdOps;
		if (ops == null) {
			throw new IllegalStateException("simd acceleration runtime was not emitted");
		}
		List<LispVal> args = cons.toList();
		// Make sure the bridge class is defined before its method reference resolves.
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get("init")).index());
		if (LispNames.SIMD_SUM.equals(member)) {
			requireArity(args.size() == 2, "simd:sum expects (simd:sum vector)");
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		}
		else {
			// add / sub / mul / scale / dot are all binary.
			requireArity(args.size() == 3, "simd:" + member + " expects two arguments");
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get(member)).index());
	}

	private static void requireArity(boolean ok, String message) {
		if (!ok) {
			throw new UnsupportedOperationException(message);
		}
	}

}
