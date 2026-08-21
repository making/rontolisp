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
 * embed the bridge at all.
 *
 * <p>
 * The set is the interpreter's ({@code eval/LinalgGpu}) exactly. It is NARROWER than
 * {@link JvmLinalgBlas}'s in one direction -- the two gemv shapes are memory-bound, so
 * their whole cost is one pass over an operand a device would have to be handed anyway,
 * and a round trip cannot win that race -- and WIDER in two others: {@code --blas} stops
 * at {@code dot}, while a batch axis is free on a device ({@code blockIdx.z}) and a
 * transcendental is 9-394x faster on one. {@code linalg:matmul} at every rank,
 * {@code linalg:solve}, {@code linalg:square} and {@code torch:gelu} are accelerated
 * transitively, through the same spliced call sites.
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
	 * The element-wise members, each mapped to the bridge method backing it -- which is
	 * also its {@code ops} key ({@link JvmGpuRuntimeBuilder}).
	 */
	private static final Map<String, String> MAP_KERNELS = Map.ofEntries(Map.entry(LispNames.LINALG_EXP, "gpuExp"),
			Map.entry(LispNames.LINALG_LOG, "gpuLog"), Map.entry(LispNames.LINALG_TANH, "gpuTanh"),
			Map.entry(LispNames.LINALG_SIN, "gpuSin"), Map.entry(LispNames.LINALG_COS, "gpuCos"),
			Map.entry(LispNames.LINALG_TAN, "gpuTan"), Map.entry(LispNames.LINALG_ASIN, "gpuAsin"),
			Map.entry(LispNames.LINALG_ACOS, "gpuAcos"), Map.entry(LispNames.LINALG_ATAN, "gpuAtan"),
			Map.entry(LispNames.LINALG_SINH, "gpuSinh"), Map.entry(LispNames.LINALG_COSH, "gpuCosh"),
			Map.entry(LispNames.LINALG_ERF, "gpuErf"));

	/**
	 * Every qualified name the emit gate scans for. A program that reaches none of them
	 * embeds no bridge, which is why a transformer -- whose only product is the stacked
	 * one -- had to put more than {@code dot} in here.
	 * @return the qualified member names this bridge accelerates
	 */
	static List<String> qualifiedMembers() {
		List<String> names = new ArrayList<>(List.of(QUALIFIED_DOT, QUALIFIED_MATMUL_ND));
		for (String member : MAP_KERNELS.keySet()) {
			names.add(PackageRegistry.qualify(LispNames.LINALG_PKG, member));
		}
		names.sort(String::compareTo);
		return names;
	}

	/** Whether the device bridge accelerates the given {@code linalg:} member. */
	static boolean handles(String member) {
		return LispNames.LINALG_DOT.equals(member) || LispNames.LINALG_MATMUL_ND.equals(member)
				|| MAP_KERNELS.containsKey(member);
	}

	/** The {@code ops} key of the bridge kernel backing the given member. */
	static String kernelKey(String member) {
		if (LispNames.LINALG_MATMUL_ND.equals(member)) {
			return JvmGpuRuntimeBuilder.MATMUL_ND;
		}
		String map = MAP_KERNELS.get(member);
		return map != null ? map : JvmGpuRuntimeBuilder.DOT;
	}

}
