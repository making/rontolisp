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
 * Compiles the accelerated {@code vec:} kernels ({@code add}/{@code sub}/{@code mul}/
 * {@code scale}/{@code dot}/{@code sum}/{@code matvec}) to calls into the embedded
 * {@link JvmSimdVectorTemplate bridge}, replacing the scalar {@code vec.lisp} reference
 * at those call sites. Only wired in when the {@code --simd} flag emitted the runtime
 * (i.e. {@link JvmLispCompiler.Ctx#simdOps} is non-null); otherwise the qualified call
 * falls through to the ordinary spliced {@code vec:} defun. Each call site first invokes
 * the emitted {@code _simdInit} helper (which lazily defines the bridge, see
 * {@link JvmSimdRuntimeBuilder}), then evaluates the arguments and calls the matching
 * bridge entry point. {@code mean}/{@code norm} are not intercepted directly -- they are
 * accelerated transitively because their spliced bodies call {@code sum}/{@code dot}.
 */
final class JvmSimdCompiler {

	private JvmSimdCompiler() {
	}

	/** The accelerated members, mapped to the argument count of their Lisp call form. */
	private static final Map<String, Integer> ARITIES = Map.ofEntries(Map.entry(LispNames.VEC_SUM, 1),
			Map.entry(LispNames.VEC_ADD, 2), Map.entry(LispNames.VEC_SUB, 2), Map.entry(LispNames.VEC_MUL, 2),
			Map.entry(LispNames.VEC_SCALE, 2), Map.entry(LispNames.VEC_DOT, 2), Map.entry(LispNames.VEC_MATVEC, 2),
			Map.entry(LispNames.VEC_ADD_INTO, 3), Map.entry(LispNames.VEC_SUB_INTO, 3),
			Map.entry(LispNames.VEC_MUL_INTO, 3), Map.entry(LispNames.VEC_SCALE_INTO, 3),
			Map.entry(LispNames.VEC_MATVEC_INTO, 3));

	/**
	 * Returns whether the given {@code simd} package member is one of the vectorizable
	 * kernels this compiler accelerates.
	 */
	static boolean handles(String member) {
		return ARITIES.containsKey(member);
	}

	/** The accelerated member names, in a stable order (the {@code --simd} emit gate). */
	static List<String> members() {
		return List.of(LispNames.VEC_ADD, LispNames.VEC_SUB, LispNames.VEC_MUL, LispNames.VEC_SCALE, LispNames.VEC_DOT,
				LispNames.VEC_SUM, LispNames.VEC_MATVEC, LispNames.VEC_ADD_INTO, LispNames.VEC_SUB_INTO,
				LispNames.VEC_MUL_INTO, LispNames.VEC_SCALE_INTO, LispNames.VEC_MATVEC_INTO);
	}

	static void compile(String member, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		Map<String, MethodrefConstant> ops = ctx.simdOps;
		if (ops == null) {
			throw new IllegalStateException("simd acceleration runtime was not emitted");
		}
		List<LispVal> args = cons.toList();
		int arity = Objects.requireNonNull(ARITIES.get(member));
		requireArity(args.size() == arity + 1,
				"vec:" + member + " expects " + arity + " argument" + (arity == 1 ? "" : "s"));
		// Make sure the bridge class is defined before its method reference resolves.
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get("init")).index());
		for (int i = 1; i <= arity; i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
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
