package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;

/**
 * What {@code --gpu} accelerates on the JVM backend: the {@code linalg:} matrix product
 * -- {@code linalg:dot}'s MATRIX-BY-MATRIX case and the STACKED rank-&gt;=3 product
 * behind {@code linalg:matmul} -- plus the ELEMENT-WISE tier, the twelve unary ufuncs
 * whose scalar cost is a libm call. Nothing else. The emission belongs to
 * {@link JvmLinalgKernelCompiler}, which chains this attempt ahead of the {@code --blas}
 * and {@code --simd} ones over shared temps; this class only says which members the
 * {@link JvmGpuTemplate device bridge} claims, so the compiler can decide whether to
 * embed the bridge at all -- plus {@code vec:matvec}, the one member outside
 * {@code linalg:}, whose call site belongs to {@link JvmSimdCompiler} and whose name is
 * here for the gate alone.
 *
 * <p>
 * The set is the interpreter's ({@code eval/LinalgGpu}) exactly. It is NARROWER than
 * {@link JvmLinalgBlas}'s in one direction -- the two {@code linalg:} gemv shapes are
 * memory-bound, so their whole cost is one pass over an operand a device would have to be
 * handed anyway, and a round trip cannot win that race (which is also why
 * {@code vec:matvec} is taken only over a matrix that STAYS on the device) -- and WIDER
 * in two others: {@code --blas} stops at {@code dot}, while a batch axis is free on a
 * device ({@code blockIdx.z}) and a transcendental is 9-394x faster on one.
 * {@code linalg:matmul} at every rank, {@code linalg:solve}, {@code linalg:square} and
 * {@code torch:gelu} are accelerated transitively, through the same spliced call sites.
 *
 * <p>
 * {@code sqrt}, {@code abs}, {@code negative}, {@code sign} and the binary
 * {@code add}/{@code sub}/{@code mul}/{@code div} are DECLINED members of the same tier:
 * one machine instruction over one or three streams, which a round trip cannot pay for.
 * {@code .kb/gpu.md} has both halves of that measurement.
 */
final class JvmLinalgGpu {

	private JvmLinalgGpu() {
	}

	/** The rank-2 member's qualified spelling (part of the emit gate's scan key). */
	static final String QUALIFIED_DOT = PackageRegistry.qualify(LispNames.LINALG_PKG, LispNames.LINALG_DOT);

	/**
	 * The stacked member's, which carries the DOUBLE colon: a {@code %}-prefixed member
	 * is an internal symbol ({@code .kb/linalg-simd.md}).
	 */
	static final String QUALIFIED_MATMUL_ND = PackageRegistry.qualifyInternal(LispNames.LINALG_PKG,
			LispNames.LINALG_MATMUL_ND);

	/**
	 * The seeded generator's fill ({@code linalg::%la-rng-fill}, behind {@code rand} /
	 * {@code randn} / {@code uniform}), the one member here with no operand to copy up
	 * and a result that is byte-identical to the CPU's.
	 */
	static final String QUALIFIED_RNG_FILL = PackageRegistry.qualifyInternal(LispNames.LINALG_PKG,
			LispNames.LINALG_RNG_FILL);

	/**
	 * The one device member OUTSIDE {@code linalg:}: {@code vec:matvec}, the GEMV a
	 * decode loop is made of, accepted only over a matrix that stays resident. Its call
	 * site is {@link JvmSimdCompiler}'s, not {@link JvmLinalgKernelCompiler}'s, but it is
	 * in this gate so that a program whose only device member it is embeds the bridge.
	 */
	static final String QUALIFIED_VEC_MATVEC = PackageRegistry.qualify(LispNames.VEC_PKG, LispNames.VEC_MATVEC);

	/**
	 * The element-wise members, each mapped to the bridge method backing it -- which is
	 * also its {@code ops} key ({@link JvmGpuRuntimeBuilder}).
	 */
	private static final Map<String, String> MAP_KERNELS = Map.ofEntries(Map.entry(LispNames.LINALG_EXP, "gpuExp"),
			Map.entry(LispNames.LINALG_LOG, "gpuLog"), Map.entry(LispNames.LINALG_TANH, "gpuTanh"),
			Map.entry(LispNames.LINALG_SIN, "gpuSin"), Map.entry(LispNames.LINALG_COS, "gpuCos"),
			Map.entry(LispNames.LINALG_TAN, "gpuTan"), Map.entry(LispNames.LINALG_ASIN, "gpuAsin"),
			Map.entry(LispNames.LINALG_ACOS, "gpuAcos"), Map.entry(LispNames.LINALG_ATAN, "gpuAtan"),
			Map.entry(LispNames.LINALG_SINH, "gpuSinh"), Map.entry(LispNames.LINALG_COSH, "gpuCosh"),
			Map.entry(LispNames.LINALG_ERF, "gpuErf"),
			// The resident tier's four maps: members over a RESIDENT operand only.
			Map.entry(LispNames.LINALG_SQRT, "gpuSqrt"), Map.entry(LispNames.LINALG_ABS, "gpuAbs"),
			Map.entry(LispNames.LINALG_NEGATIVE, "gpuNegative"), Map.entry(LispNames.LINALG_SIGN, "gpuSign"));

	/**
	 * The STRIDED tier's BASE call shapes: the binary element-wise members, taken only at
	 * a BROADCAST shape (the bridge declines an equal-shaped pair, which is the case the
	 * element-wise tier measured and refused). Same convention: the value is the bridge
	 * method and the {@code ops} key at once.
	 */
	private static final Map<String, String> BIN_KERNELS = Map.ofEntries(Map.entry(LispNames.LINALG_ADD, "gpuAdd"),
			Map.entry(LispNames.LINALG_SUB, "gpuSub"), Map.entry(LispNames.LINALG_MUL, "gpuMul"),
			Map.entry(LispNames.LINALG_DIV, "gpuDiv"), Map.entry(LispNames.LINALG_MAXIMUM, "gpuMaximum"),
			Map.entry(LispNames.LINALG_MINIMUM, "gpuMinimum"),
			// The comparison masks, at the same three shapes (broadcast, equal, scalar).
			Map.entry(LispNames.LINALG_GREATER, "gpuGreater"),
			Map.entry(LispNames.LINALG_GREATER_EQUAL, "gpuGreaterEqual"), Map.entry(LispNames.LINALG_LESS, "gpuLess"),
			Map.entry(LispNames.LINALG_LESS_EQUAL, "gpuLessEqual"), Map.entry(LispNames.LINALG_EQUAL, "gpuEqual"),
			// The rest of the resident tier: the three-way select, the Adam update, and
			// the
			// COPY members -- reshape, slice's strided gather, the in-place clip scale.
			Map.entry(LispNames.LINALG_WHERE, JvmGpuRuntimeBuilder.WHERE),
			Map.entry(LispNames.LINALG_ADAM_STEP, JvmGpuRuntimeBuilder.ADAM_STEP),
			Map.entry(LispNames.LINALG_RESHAPE, "gpuReshape"),
			Map.entry(LispNames.LINALG_GATHER_STRIDED, JvmGpuRuntimeBuilder.GATHER_STRIDED),
			Map.entry(LispNames.LINALG_SCALE, "gpuScale"),
			// The plain (rank-2) transpose; the axes form is in EXT_KERNELS.
			Map.entry(LispNames.LINALG_TRANSPOSE, "gpuTranspose"),
			// The INDEX tier and the clip norm's sum of squares: the
			// embedding lookup, its scatter-add adjoint, the cross-entropy pick and the
			// fold every gradient of the model used to come home for.
			Map.entry(LispNames.LINALG_TAKE_ROWS, "gpuTakeRows"), Map.entry(LispNames.LINALG_GATHER, "gpuPick"),
			Map.entry(LispNames.LINALG_SCATTER_ROWS, JvmGpuRuntimeBuilder.SCATTER_ROWS),
			Map.entry(LispNames.LINALG_SUM_SQUARES, "gpuSumSquares"));

	/**
	 * The STRIDED tier's EXTENDED (option-form) call shapes -- the axis folds and the
	 * axes transpose. These members have NO base-shape kernel: a whole-array fold is one
	 * output cell, which on a device is a single-threaded loop, and a plain rank-2
	 * transpose is the {@code --simd} lane form's own shape.
	 */
	private static final Map<String, String> EXT_KERNELS = Map.of(LispNames.LINALG_SUM, "gpuSumAxis",
			LispNames.LINALG_AMAX, "gpuAmaxAxis", LispNames.LINALG_AMIN, "gpuAminAxis", LispNames.LINALG_TRANSPOSE,
			"gpuTransposeAxes", LispNames.LINALG_CONCATENATE, "gpuConcatenate",
			// softmax over its :axis (the fused tier, todo-499).
			LispNames.LINALG_SOFTMAX, "gpuSoftmaxAxis");

	/**
	 * The FUSED tier's base-shape members ({@code .todo/499}): the compositions
	 * {@code torch.lisp} spells as one internal member each so the device can run them as
	 * one pass -- the exact GELU and its adjoint, softmax's adjoint, layer-norm's
	 * normalization and its adjoint, and the dropout mask. Same convention as the maps.
	 */
	private static final Map<String, String> FUSED_KERNELS = Map.of(LispNames.LINALG_GELU, "gpuGelu",
			LispNames.LINALG_GELU_GRAD, "gpuGeluGrad", LispNames.LINALG_SOFTMAX_GRAD, "gpuSoftmaxGrad",
			LispNames.LINALG_LAYER_NORM, "gpuLayerNorm", LispNames.LINALG_LAYER_NORM_GRAD, "gpuLayerNormGrad",
			LispNames.LINALG_DROPOUT_MASK, "gpuDropoutMask");

	/**
	 * Every qualified name the emit gate scans for. A program that reaches none of them
	 * embeds no bridge, which is why a transformer -- whose only product is the stacked
	 * one -- had to put more than {@code dot} in here.
	 * @return the qualified member names this bridge accelerates
	 */
	static List<String> qualifiedMembers() {
		List<String> names = new ArrayList<>(
				List.of(QUALIFIED_DOT, QUALIFIED_MATMUL_ND, QUALIFIED_RNG_FILL, QUALIFIED_VEC_MATVEC));
		for (String member : MAP_KERNELS.keySet()) {
			names.add(PackageRegistry.qualify(LispNames.LINALG_PKG, member));
		}
		for (String member : BIN_KERNELS.keySet()) {
			names.add(member.startsWith("%") ? PackageRegistry.qualifyInternal(LispNames.LINALG_PKG, member)
					: PackageRegistry.qualify(LispNames.LINALG_PKG, member));
		}
		for (String member : FUSED_KERNELS.keySet()) {
			names.add(PackageRegistry.qualifyInternal(LispNames.LINALG_PKG, member));
		}
		for (String member : EXT_KERNELS.keySet()) {
			String qualified = PackageRegistry.qualify(LispNames.LINALG_PKG, member);
			if (!names.contains(qualified)) {
				names.add(qualified);
			}
		}
		names.sort(String::compareTo);
		return names;
	}

	/** Whether the device bridge accelerates the given {@code linalg:} member at all. */
	static boolean handles(String member) {
		return kernelKey(member) != null || EXT_KERNELS.containsKey(member);
	}

	/**
	 * The {@code ops} key of the bridge kernel backing the member's BASE call shape, or
	 * {@code null} when it has none -- which is the case for every member the device
	 * takes only in its option form.
	 */
	static @org.jspecify.annotations.Nullable String kernelKey(String member) {
		if (LispNames.LINALG_DOT.equals(member)) {
			return JvmGpuRuntimeBuilder.DOT;
		}
		if (LispNames.LINALG_MATMUL_ND.equals(member)) {
			return JvmGpuRuntimeBuilder.MATMUL_ND;
		}
		if (LispNames.LINALG_RNG_FILL.equals(member)) {
			return JvmGpuRuntimeBuilder.RNG_FILL;
		}
		String map = MAP_KERNELS.get(member);
		if (map != null) {
			return map;
		}
		String bin = BIN_KERNELS.get(member);
		return bin != null ? bin : FUSED_KERNELS.get(member);
	}

	/**
	 * The {@code ops} key of the bridge kernel backing the member's EXTENDED (option
	 * form) call shape, or {@code null} when the device does not take that shape. The
	 * parameters are the {@code --simd} extended kernel's, in the same order, so one
	 * {@link am.ik.rontolisp.compiler.LinalgKernelCallLayout} serves both attempts.
	 */
	static @org.jspecify.annotations.Nullable String extendedKernelKey(String member) {
		return EXT_KERNELS.get(member);
	}

}
