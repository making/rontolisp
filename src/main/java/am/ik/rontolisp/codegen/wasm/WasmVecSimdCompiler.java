package am.ik.rontolisp.codegen.wasm;

import java.util.Arrays;
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
			Map.entry(LispNames.VEC_CLIP_INTO, WasmVecSimdRuntimeBuilder.CLIP_INTO),
			// The element-wise quotient and the four CL operator spellings, which reuse
			// the very helpers their named siblings call.
			Map.entry(LispNames.VEC_DIV, WasmVecSimdRuntimeBuilder.DIV),
			Map.entry(LispNames.VEC_DIV_INTO, WasmVecSimdRuntimeBuilder.DIV_INTO),
			Map.entry(LispNames.VEC_PLUS, WasmVecSimdRuntimeBuilder.ADD),
			Map.entry(LispNames.VEC_MINUS, WasmVecSimdRuntimeBuilder.SUB),
			Map.entry(LispNames.VEC_STAR, WasmVecSimdRuntimeBuilder.MUL),
			Map.entry(LispNames.VEC_SLASH, WasmVecSimdRuntimeBuilder.DIV));

	/** The argument count of each accelerated member's Lisp call form. */
	private static int arity(String member) {
		return switch (member) {
			case LispNames.VEC_SUM, LispNames.VEC_EXP, LispNames.VEC_LOG, LispNames.VEC_TANH, LispNames.VEC_SIN,
					LispNames.VEC_COS, LispNames.VEC_TAN, LispNames.VEC_ASIN, LispNames.VEC_ACOS, LispNames.VEC_ATAN,
					LispNames.VEC_SINH, LispNames.VEC_COSH, LispNames.VEC_SQRT, LispNames.VEC_ABS,
					LispNames.VEC_NEGATIVE, LispNames.VEC_SIGN, LispNames.VEC_RECIPROCAL, LispNames.VEC_RELU ->
				1;
			case LispNames.VEC_ADD_INTO, LispNames.VEC_SUB_INTO, LispNames.VEC_MUL_INTO, LispNames.VEC_DIV_INTO,
					LispNames.VEC_SCALE_INTO, LispNames.VEC_MATVEC_INTO, LispNames.VEC_CLIP, LispNames.VEC_MAXIMUM_INTO,
					LispNames.VEC_MINIMUM_INTO ->
				3;
			case LispNames.VEC_CLIP_INTO -> 4;
			default -> 2;
		};
	}

	/**
	 * The four CL operator spellings, which have no helper of their own: each maps onto
	 * the helper of its named sibling, so the scalar-fallback table below reads its entry
	 * from that sibling's defun and only falls back to the operator's own when the named
	 * one is not in the program.
	 */
	private static final List<String> OPERATOR_SPELLINGS = List.of(LispNames.VEC_PLUS, LispNames.VEC_MINUS,
			LispNames.VEC_STAR, LispNames.VEC_SLASH);

	/**
	 * The module function index of the spliced {@code vec.lisp} defun behind each helper,
	 * indexed by {@link WasmVecSimdRuntimeBuilder} offset, {@code -1} where the program
	 * has no such defun: what a helper hands a WIDTH-MISMATCHED call back to instead of
	 * trapping ({@code .kb/vec.md}, "The four acceleration layers"). A mismatch is not an
	 * error -- the scalar defun reads every operand through {@code aref}, which widens --
	 * and the lane kernels carry no mixed-width form, so the decline has to leave the
	 * accelerator, exactly as the interpreter's {@code VecSimd} and the JVM's lane-width
	 * guard do.
	 *
	 * <p>
	 * The entry is only used on the mismatch arm, so a member whose defun the program
	 * does not hold (a shadowing redefinition of a different arity, a member the
	 * tree-shaker dropped because nothing calls it) leaves its helper trapping as before
	 * -- no call site can reach a helper whose defun the program lacks, since the call
	 * site IS what keeps the defun reachable.
	 */
	static int[] scalarFallbacks(Map<String, WasmLispCompiler.WasmFunctionInfo> functions) {
		int[] fallbacks = new int[WasmVecSimdRuntimeBuilder.FUNC_COUNT];
		Arrays.fill(fallbacks, -1);
		for (Map.Entry<String, Integer> kernel : KERNELS.entrySet()) {
			String member = kernel.getKey();
			int offset = kernel.getValue();
			WasmLispCompiler.WasmFunctionInfo defun = functions.get(LispNames.VEC_PKG + ":" + member);
			if (defun == null || defun.variadic() || defun.paramCount() != arity(member)) {
				continue;
			}
			// The named member owns the entry whatever order the map iterates in; an
			// operator spelling only fills one its sibling left empty.
			if (!OPERATOR_SPELLINGS.contains(member) || fallbacks[offset] < 0) {
				fallbacks[offset] = defun.funcIndex();
			}
		}
		return fallbacks;
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
