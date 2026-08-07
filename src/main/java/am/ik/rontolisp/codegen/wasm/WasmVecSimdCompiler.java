package am.ik.rontolisp.codegen.wasm;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the accelerated {@code vec:} kernels ({@code add}/{@code sub}/{@code mul}/
 * {@code scale}/{@code dot}/{@code sum}/{@code matvec} and their five {@code -into}
 * siblings) to calls into the emitted v128 runtime helpers
 * ({@link WasmVecSimdRuntimeBuilder}), replacing the scalar {@code vec.lisp} defun at
 * those call sites. The wasm-GC counterpart of {@code JvmSimdCompiler}, and wired in the
 * same way: only when {@code --simd} emitted the helpers
 * ({@link WasmLispCompiler.Ctx#simd}), otherwise the qualified call falls through to the
 * ordinary spliced defun.
 *
 * <p>
 * {@code mean}/{@code norm} are not intercepted directly -- they are accelerated
 * transitively, because their spliced bodies call {@code sum}/{@code dot}. Nor is
 * {@code #'vec:dot}: a first-class function value still refers to the scalar defun,
 * exactly as on the JVM.
 */
final class WasmVecSimdCompiler {

	private WasmVecSimdCompiler() {
	}

	/**
	 * The accelerated members, mapped to their {@link WasmVecSimdRuntimeBuilder} function
	 * offset. The Lisp call form's argument count is the helper's parameter count.
	 */
	private static final Map<String, Integer> KERNELS = Map.ofEntries(
			Map.entry(LispNames.VEC_ADD, WasmVecSimdRuntimeBuilder.ADD),
			Map.entry(LispNames.VEC_SUB, WasmVecSimdRuntimeBuilder.SUB),
			Map.entry(LispNames.VEC_MUL, WasmVecSimdRuntimeBuilder.MUL),
			Map.entry(LispNames.VEC_SCALE, WasmVecSimdRuntimeBuilder.SCALE),
			Map.entry(LispNames.VEC_SUM, WasmVecSimdRuntimeBuilder.SUM),
			Map.entry(LispNames.VEC_DOT, WasmVecSimdRuntimeBuilder.DOT),
			Map.entry(LispNames.VEC_MATVEC, WasmVecSimdRuntimeBuilder.MATVEC),
			Map.entry(LispNames.VEC_ADD_INTO, WasmVecSimdRuntimeBuilder.ADD_INTO),
			Map.entry(LispNames.VEC_SUB_INTO, WasmVecSimdRuntimeBuilder.SUB_INTO),
			Map.entry(LispNames.VEC_MUL_INTO, WasmVecSimdRuntimeBuilder.MUL_INTO),
			Map.entry(LispNames.VEC_SCALE_INTO, WasmVecSimdRuntimeBuilder.SCALE_INTO),
			Map.entry(LispNames.VEC_MATVEC_INTO, WasmVecSimdRuntimeBuilder.MATVEC_INTO),
			// The element-wise unary ufuncs. vec:square / vec:square-into are
			// not here: their spliced defuns call vec:mul / vec:mul-into, so they are
			// accelerated transitively, like mean/norm.
			Map.entry(LispNames.VEC_EXP, WasmVecSimdRuntimeBuilder.EXP),
			Map.entry(LispNames.VEC_LOG, WasmVecSimdRuntimeBuilder.LOG),
			Map.entry(LispNames.VEC_TANH, WasmVecSimdRuntimeBuilder.TANH),
			Map.entry(LispNames.VEC_SIN, WasmVecSimdRuntimeBuilder.SIN),
			Map.entry(LispNames.VEC_COS, WasmVecSimdRuntimeBuilder.COS),
			Map.entry(LispNames.VEC_TAN, WasmVecSimdRuntimeBuilder.TAN),
			Map.entry(LispNames.VEC_ASIN, WasmVecSimdRuntimeBuilder.ASIN),
			Map.entry(LispNames.VEC_ACOS, WasmVecSimdRuntimeBuilder.ACOS),
			Map.entry(LispNames.VEC_ATAN, WasmVecSimdRuntimeBuilder.ATAN),
			Map.entry(LispNames.VEC_SINH, WasmVecSimdRuntimeBuilder.SINH),
			Map.entry(LispNames.VEC_COSH, WasmVecSimdRuntimeBuilder.COSH),
			Map.entry(LispNames.VEC_SQRT, WasmVecSimdRuntimeBuilder.SQRT),
			Map.entry(LispNames.VEC_ABS, WasmVecSimdRuntimeBuilder.ABS),
			Map.entry(LispNames.VEC_NEGATIVE, WasmVecSimdRuntimeBuilder.NEGATIVE),
			Map.entry(LispNames.VEC_SIGN, WasmVecSimdRuntimeBuilder.SIGN),
			Map.entry(LispNames.VEC_RECIPROCAL, WasmVecSimdRuntimeBuilder.RECIPROCAL),
			Map.entry(LispNames.VEC_EXP_INTO, WasmVecSimdRuntimeBuilder.EXP_INTO),
			Map.entry(LispNames.VEC_LOG_INTO, WasmVecSimdRuntimeBuilder.LOG_INTO),
			Map.entry(LispNames.VEC_TANH_INTO, WasmVecSimdRuntimeBuilder.TANH_INTO),
			Map.entry(LispNames.VEC_SIN_INTO, WasmVecSimdRuntimeBuilder.SIN_INTO),
			Map.entry(LispNames.VEC_COS_INTO, WasmVecSimdRuntimeBuilder.COS_INTO),
			Map.entry(LispNames.VEC_TAN_INTO, WasmVecSimdRuntimeBuilder.TAN_INTO),
			Map.entry(LispNames.VEC_ASIN_INTO, WasmVecSimdRuntimeBuilder.ASIN_INTO),
			Map.entry(LispNames.VEC_ACOS_INTO, WasmVecSimdRuntimeBuilder.ACOS_INTO),
			Map.entry(LispNames.VEC_ATAN_INTO, WasmVecSimdRuntimeBuilder.ATAN_INTO),
			Map.entry(LispNames.VEC_SINH_INTO, WasmVecSimdRuntimeBuilder.SINH_INTO),
			Map.entry(LispNames.VEC_COSH_INTO, WasmVecSimdRuntimeBuilder.COSH_INTO),
			Map.entry(LispNames.VEC_SQRT_INTO, WasmVecSimdRuntimeBuilder.SQRT_INTO),
			Map.entry(LispNames.VEC_ABS_INTO, WasmVecSimdRuntimeBuilder.ABS_INTO),
			Map.entry(LispNames.VEC_NEGATIVE_INTO, WasmVecSimdRuntimeBuilder.NEGATIVE_INTO),
			Map.entry(LispNames.VEC_SIGN_INTO, WasmVecSimdRuntimeBuilder.SIGN_INTO),
			Map.entry(LispNames.VEC_RECIPROCAL_INTO, WasmVecSimdRuntimeBuilder.RECIPROCAL_INTO),
			// The comparison-select ufuncs.
			Map.entry(LispNames.VEC_MAXIMUM, WasmVecSimdRuntimeBuilder.MAXIMUM),
			Map.entry(LispNames.VEC_MINIMUM, WasmVecSimdRuntimeBuilder.MINIMUM),
			Map.entry(LispNames.VEC_RELU, WasmVecSimdRuntimeBuilder.RELU),
			Map.entry(LispNames.VEC_CLIP, WasmVecSimdRuntimeBuilder.CLIP),
			Map.entry(LispNames.VEC_MAXIMUM_INTO, WasmVecSimdRuntimeBuilder.MAXIMUM_INTO),
			Map.entry(LispNames.VEC_MINIMUM_INTO, WasmVecSimdRuntimeBuilder.MINIMUM_INTO),
			Map.entry(LispNames.VEC_RELU_INTO, WasmVecSimdRuntimeBuilder.RELU_INTO),
			Map.entry(LispNames.VEC_CLIP_INTO, WasmVecSimdRuntimeBuilder.CLIP_INTO));

	/** The argument count of each accelerated member's Lisp call form. */
	private static int arity(String member) {
		return switch (member) {
			case LispNames.VEC_SUM, LispNames.VEC_EXP, LispNames.VEC_LOG, LispNames.VEC_TANH, LispNames.VEC_SIN,
					LispNames.VEC_COS, LispNames.VEC_TAN, LispNames.VEC_ASIN, LispNames.VEC_ACOS, LispNames.VEC_ATAN,
					LispNames.VEC_SINH, LispNames.VEC_COSH, LispNames.VEC_SQRT, LispNames.VEC_ABS,
					LispNames.VEC_NEGATIVE, LispNames.VEC_SIGN, LispNames.VEC_RECIPROCAL, LispNames.VEC_RELU ->
				1;
			case LispNames.VEC_ADD_INTO, LispNames.VEC_SUB_INTO, LispNames.VEC_MUL_INTO, LispNames.VEC_SCALE_INTO,
					LispNames.VEC_MATVEC_INTO, LispNames.VEC_CLIP, LispNames.VEC_MAXIMUM_INTO,
					LispNames.VEC_MINIMUM_INTO ->
				3;
			case LispNames.VEC_CLIP_INTO -> 4;
			default -> 2;
		};
	}

	/** Returns whether the given qualified name is a kernel this compiler accelerates. */
	static boolean handles(String qualifiedName) {
		return KERNELS.containsKey(member(qualifiedName));
	}

	/**
	 * The member part of a {@code vec:}-qualified name ({@code "vec:dot"} ->
	 * {@code "dot"}).
	 */
	private static String member(String qualifiedName) {
		if (!qualifiedName.startsWith(LispNames.VEC_PKG + ":")) {
			return "";
		}
		return qualifiedName.substring(qualifiedName.lastIndexOf(':') + 1);
	}

	/** Emits {@code <args...> call $_vec_<member>} for an accelerated call site. */
	static void compile(String qualifiedName, LispCons cons, WasmLispCompiler.Ctx ctx) {
		String member = member(qualifiedName);
		int offset = Objects.requireNonNull(KERNELS.get(member));
		int arity = arity(member);
		List<LispVal> args = cons.toList();
		if (args.size() != arity + 1) {
			throw new UnsupportedOperationException("vec:" + member + " expects " + arity + " argument"
					+ (arity == 1 ? "" : "s") + ", got " + (args.size() - 1));
		}
		for (int i = 1; i <= arity; i++) {
			WasmExprCompiler.compileExpr(args.get(i), ctx);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_VEC_BASE + offset);
	}

}
