package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.LinalgKernelCallLayout;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

/**
 * Compiles the accelerated {@code linalg:} kernels to calls into the embedded bridges,
 * the {@code linalg:} sibling of {@link JvmSimdCompiler}. It is the ONE {@code linalg:}
 * call-site compiler, and it emits a CHAIN of up to three attempts over one set of temps:
 * the device ({@code --gpu}, {@link JvmGpuTemplate}: the rank-2 product and the stacked
 * one), then a tuned CBLAS ({@code --blas}, {@link JvmBlasTemplate}), then the lane
 * kernel ({@code --simd}, {@link JvmSimdVectorTemplate}), then the scalar
 * {@code linalg.lisp} defun -- which is the interpreter's install order written as a
 * chain ({@code .kb/gpu.md}). Only wired in when at least one of the three flags emitted
 * its runtime ({@link JvmLispCompiler.Ctx}'s {@code simdOps} / {@code blasOps} /
 * {@code gpuOps}); otherwise the qualified call falls through to the ordinary spliced
 * defun.
 *
 * <h2>Why this call site is not simply a bridge call</h2>
 *
 * {@code vec:} accepts packed float arrays and nothing else, so {@link JvmSimdCompiler}
 * can emit a bare {@code INVOKESTATIC}. {@code linalg:} also accepts general (boxed)
 * arrays, mixed widths, a scalar operand on either side, plain numbers, and mismatched
 * shapes (a specific {@code error}). Reproducing all of that in the bridge would
 * duplicate the library. Instead each bridge kernel is PARTIAL: it returns {@code null}
 * for an input it does not handle, and this compiler emits
 *
 * <pre>
 *   _simdInit(); a = &lt;arg1&gt;; b = &lt;arg2&gt;;          // evaluated ONCE, into temps
 *   r = Bridge.laAdd(a, b);
 *   if (r == null) r = linalg$colonadd(a, b);      // the scalar defun
 * </pre>
 *
 * The temps are what make the fallback safe: recompiling the argument forms would
 * evaluate their side effects twice. {@code null} is an unambiguous "declined" because
 * compiled nil is {@code ACONST_NULL} and none of the intercepted members ever returns
 * nil -- {@code linalg:array-equal}, which does, is deliberately not intercepted.
 *
 * <p>
 * {@code mean}, {@code matmul}, {@code flatten} and {@code solve} are accelerated
 * TRANSITIVELY: their spliced bodies call {@code sum} / {@code dot} / {@code reshape},
 * whose call sites are intercepted. Nor is {@code #'linalg:dot}: a first-class function
 * value still names the scalar defun, exactly as with {@code vec:}.
 *
 * <p>
 * {@code JvmClassShaker} needs no new root: the emitted call site has an ordinary
 * {@code INVOKESTATIC} edge to both the bridge init and the scalar defun, so the defun
 * stays reachable (and therefore un-shaken) wherever a kernel can decline.
 */
final class JvmLinalgKernelCompiler {

	private JvmLinalgKernelCompiler() {
	}

	/** The accelerated members, in a stable order, mapped to their bridge method. */
	private static final Map<String, String> KERNELS = Map.ofEntries(Map.entry(LispNames.LINALG_ADD, "laAdd"),
			Map.entry(LispNames.LINALG_SUB, "laSub"), Map.entry(LispNames.LINALG_MUL, "laMul"),
			Map.entry(LispNames.LINALG_DIV, "laDiv"), Map.entry(LispNames.LINALG_SUM, "laSum"),
			Map.entry(LispNames.LINALG_NORM, "laNorm"), Map.entry(LispNames.LINALG_AMAX, "laAmax"),
			Map.entry(LispNames.LINALG_AMIN, "laAmin"), Map.entry(LispNames.LINALG_ARGMAX, "laArgmax"),
			Map.entry(LispNames.LINALG_ARGMIN, "laArgmin"), Map.entry(LispNames.LINALG_TRACE, "laTrace"),
			Map.entry(LispNames.LINALG_TRANSPOSE, "laTranspose"), Map.entry(LispNames.LINALG_RESHAPE, "laReshape"),
			Map.entry(LispNames.LINALG_DOT, "laDot"), Map.entry(LispNames.LINALG_OUTER, "laOuter"),
			// The element-wise unary ufuncs. linalg:square / linalg:reciprocal
			// are not here: their spliced defuns call linalg:mul / linalg:div, so they
			// are accelerated transitively, like mean/matmul.
			Map.entry(LispNames.LINALG_EXP, "laExp"), Map.entry(LispNames.LINALG_LOG, "laLog"),
			Map.entry(LispNames.LINALG_TANH, "laTanh"), Map.entry(LispNames.LINALG_SIN, "laSin"),
			Map.entry(LispNames.LINALG_COS, "laCos"), Map.entry(LispNames.LINALG_TAN, "laTan"),
			Map.entry(LispNames.LINALG_ASIN, "laAsin"), Map.entry(LispNames.LINALG_ACOS, "laAcos"),
			Map.entry(LispNames.LINALG_ATAN, "laAtan"), Map.entry(LispNames.LINALG_SINH, "laSinh"),
			Map.entry(LispNames.LINALG_COSH, "laCosh"), Map.entry(LispNames.LINALG_SQRT, "laSqrt"),
			Map.entry(LispNames.LINALG_ABS, "laAbs"), Map.entry(LispNames.LINALG_NEGATIVE, "laNegative"),
			Map.entry(LispNames.LINALG_SIGN, "laSign"),
			// linalg:erf: the one activation primitive whose defun is an emap (never
			// intercepted), so the member itself is. A scalar series loop -- the
			// iteration count is data-dependent, so there is no lane form.
			Map.entry(LispNames.LINALG_ERF, "laErf"),
			// The comparison-select ufuncs. linalg:clip / linalg:relu
			// are not here: their spliced defuns compose linalg:maximum / linalg:minimum,
			// so they are accelerated transitively, like square/reciprocal.
			Map.entry(LispNames.LINALG_MAXIMUM, "laMaximum"), Map.entry(LispNames.LINALG_MINIMUM, "laMinimum"),
			// The internal CNN window unfolding pair: pure index arithmetic,
			// intercepted because the boxed defun dominates the accelerated convolution
			// runs. %-prefixed members are internal symbols, qualified with the double
			// colon (see qualifiedName).
			Map.entry(LispNames.LINALG_IM2COL, "laIm2col"), Map.entry(LispNames.LINALG_COL2IM, "laCol2im"),
			// The internal STACKED matrix product behind linalg:matmul at rank >= 3
			// (torch.bmm): one ikj slab per batch, the same kernel dot's M.M case runs.
			Map.entry(LispNames.LINALG_MATMUL_ND, "laMatmulNd"));

	/** The unary members; everything else takes two arguments. */
	private static final List<String> UNARY = List.of(LispNames.LINALG_SUM, LispNames.LINALG_NORM,
			LispNames.LINALG_AMAX, LispNames.LINALG_AMIN, LispNames.LINALG_ARGMAX, LispNames.LINALG_ARGMIN,
			LispNames.LINALG_TRACE, LispNames.LINALG_TRANSPOSE, LispNames.LINALG_EXP, LispNames.LINALG_LOG,
			LispNames.LINALG_TANH, LispNames.LINALG_SIN, LispNames.LINALG_COS, LispNames.LINALG_TAN,
			LispNames.LINALG_ASIN, LispNames.LINALG_ACOS, LispNames.LINALG_ATAN, LispNames.LINALG_SINH,
			LispNames.LINALG_COSH, LispNames.LINALG_SQRT, LispNames.LINALG_ABS, LispNames.LINALG_NEGATIVE,
			LispNames.LINALG_SIGN, LispNames.LINALG_ERF);

	/**
	 * Returns whether the given {@code linalg:} member is one this compiler accelerates.
	 */
	static boolean handles(String member) {
		return KERNELS.containsKey(member);
	}

	/** The accelerated member names, in a stable order (the {@code --simd} emit gate). */
	static List<String> members() {
		List<String> sorted = new ArrayList<>(KERNELS.keySet());
		sorted.sort(String::compareTo);
		return sorted;
	}

	/** The bridge method backing the given member. */
	static String bridgeMethod(String member) {
		return Objects.requireNonNull(KERNELS.get(member));
	}

	/** The argument count of the member's Lisp call form. */
	static int arity(String member) {
		return switch (member) {
			case LispNames.LINALG_IM2COL -> 5;
			case LispNames.LINALG_COL2IM -> 6;
			default -> UNARY.contains(member) ? 1 : 2;
		};
	}

	/**
	 * The members whose option forms have their own bridge kernel: transpose takes a
	 * positional axes permutation, sum/amax/amin the {@code :axis} / {@code :keepdims}
	 * keywords, argmax/argmin {@code :axis}. A call whose argument forms fit the member's
	 * {@link LinalgKernelCallLayout.Extended shape} routes to this bridge method (an
	 * option not supplied is padded with null = nil); on decline the surplus temps --
	 * keyword literals included -- are packaged into the variadic defun's rest list.
	 */
	record Extended(String bridgeMethod, int params) {
	}

	private static final Map<String, Extended> EXTENDED = Map.of(LispNames.LINALG_TRANSPOSE,
			new Extended("laTransposeAxes", 2), LispNames.LINALG_SUM, new Extended("laSumAxis", 3),
			LispNames.LINALG_AMAX, new Extended("laAmaxAxis", 3), LispNames.LINALG_AMIN, new Extended("laAminAxis", 3),
			LispNames.LINALG_ARGMAX, new Extended("laArgmaxAxis", 2), LispNames.LINALG_ARGMIN,
			new Extended("laArgminAxis", 2));

	/** The extended (option-form) kernel of the given member, or {@code null}. */
	static @org.jspecify.annotations.Nullable Extended extended(String member) {
		return EXTENDED.get(member);
	}

	/** The ops-map key of a member's extended bridge registration. */
	static String extendedKey(String member) {
		return qualifiedName(member) + "#ext";
	}

	/**
	 * The member's canonical qualified spelling: a {@code %}-prefixed member is an
	 * internal symbol and carries the double colon ({@code linalg::%la-im2col}), which is
	 * how the spliced defun is keyed in {@code ctx.functions} and how the program
	 * references it.
	 */
	static String qualifiedName(String member) {
		return member.startsWith("%") ? PackageRegistry.qualifyInternal(LispNames.LINALG_PKG, member)
				: PackageRegistry.qualify(LispNames.LINALG_PKG, member);
	}

	/**
	 * Whether this compiler claims the call site of the given member -- because a bridge
	 * that accelerates it was emitted. Either bridge is enough: the two flags are
	 * orthogonal, and the emitted chain simply has one attempt instead of two.
	 */
	static boolean claims(String member, JvmLispCompiler.Ctx ctx) {
		return (ctx.simdOps != null && handles(member)) || (ctx.blasOps != null && JvmLinalgBlas.handles(member))
				|| (ctx.gpuOps != null && JvmLinalgGpu.handles(member));
	}

	static void compile(String member, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		Map<String, MethodrefConstant> simd = ctx.simdOps != null && handles(member) ? ctx.simdOps : null;
		Map<String, MethodrefConstant> blas = ctx.blasOps != null && JvmLinalgBlas.handles(member) ? ctx.blasOps : null;
		Map<String, MethodrefConstant> gpu = ctx.gpuOps != null && JvmLinalgGpu.handles(member) ? ctx.gpuOps : null;
		if (simd == null && blas == null && gpu == null) {
			throw new IllegalStateException("no linalg: acceleration runtime was emitted for " + member);
		}
		String qualified = qualifiedName(member);
		JvmLispCompiler.FunctionInfo defun = ctx.functions.get(qualified);
		List<LispVal> args = cons.toList();
		int arity = arity(member);
		int supplied = args.size() - 1;
		// The option form is claimed when EITHER the lane bridge or the device bridge has
		// a kernel for it: --gpu takes the axis folds and the axes transpose only in this
		// shape, so a --gpu-only build must still reach it.
		String gpuExtendedKey = gpu != null ? JvmLinalgGpu.extendedKernelKey(member) : null;
		Extended ext = (simd != null || gpuExtendedKey != null) && supplied > arity ? EXTENDED.get(member) : null;
		LinalgKernelCallLayout.Extended shape = ext != null ? LinalgKernelCallLayout.extended(member) : null;
		int[] layout = shape != null ? LinalgKernelCallLayout.layout(shape, arity, args.subList(1, args.size())) : null;
		boolean extendedCall = layout != null;
		String gpuKey = extendedCall ? gpuExtendedKey : (gpu != null ? JvmLinalgGpu.kernelKey(member) : null);
		if (gpuKey == null && simd == null && (blas == null || extendedCall)) {
			// Nothing would be attempted at this call shape -- a member the device takes
			// only in its option form, reached in its base form under --gpu alone. The
			// ordinary direct-call path is what that is.
			JvmFunctionCallCompiler.compileDefault(qualified, cons, ctx, className);
			return;
		}
		if (defun == null || (supplied != arity && !extendedCall)
				|| (defun.variadic() ? defun.paramCount() - 1 : defun.paramCount()) != arity
				|| (extendedCall && !defun.variadic())) {
			// No spliced linalg.lisp to fall back to, a call whose option forms no kernel
			// handles, or a defun whose required count no longer matches: the ordinary
			// direct-call path handles all three.
			JvmFunctionCallCompiler.compileDefault(qualified, cons, ctx, className);
			return;
		}
		// The bridge classes must be defined before their method references resolve, and
		// ahead of the temps: with only the --simd attempt this is byte for byte the
		// sequence emitted before --blas existed.
		if (gpuKey != null && gpu != null) {
			emitInit(ctx, gpu);
		}
		Map<String, MethodrefConstant> blasAttempt = extendedCall ? null : blas;
		if (blasAttempt != null) {
			emitInit(ctx, blasAttempt);
		}
		if (simd != null) {
			emitInit(ctx, simd);
		}
		// Evaluate each argument exactly once, into a temp every branch reads.
		int[] slots = new int[supplied];
		for (int i = 0; i < supplied; i++) {
			JvmExprCompiler.compileExpr(args.get(i + 1), ctx, className);
			slots[i] = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slots[i]);
		}
		// The attempts, outermost first: the device when --gpu emitted its bridge, then
		// the library product when --blas emitted its own, then the lane kernel when
		// --simd did, then the scalar defun. That is the interpreter's install order
		// (.kb/gpu.md) written as a chain, and every prefix of it works the same way:
		// each attempt returns null for an input it declines, and control falls into the
		// next one over the SAME temps -- so a declined product always lands on the best
		// CPU path this invocation enabled, never back on the defun.
		List<Integer> takenBranches = new ArrayList<>();
		if (gpuKey != null && gpu != null) {
			emitAttempt(ctx, gpu, gpuKey, extendedCall ? layout : null, slots, arity, takenBranches);
		}
		if (blas != null && !extendedCall) {
			emitAttempt(ctx, blas, JvmBlasRuntimeBuilder.DOT, null, slots, arity, takenBranches);
		}
		if (simd != null) {
			emitAttempt(ctx, simd, extendedCall ? extendedKey(member) : qualified, layout, slots, arity, takenBranches);
		}
		for (int i = 0; i < arity; i++) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(slots[i]);
		}
		if (defun.variadic()) {
			// A defun with an &optional/&rest lambda list takes a trailing rest
			// parameter: the surplus temps are linked into a cons list (an empty rest
			// list is compiled nil, null), newest link first.
			int restSlot = ctx.allocTemp();
			ctx.emit(Opcode.ACONST_NULL);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(restSlot);
			for (int k = supplied - 1; k >= arity; k--) {
				ctx.emit(Opcode.ICONST_2);
				ctx.emit(Opcode.ANEWARRAY);
				ctx.emitU2(ctx.objectClass.index());
				ctx.emit(Opcode.DUP);
				ctx.emit(Opcode.ICONST_0);
				ctx.emit(Opcode.ALOAD);
				ctx.emit(slots[k]);
				ctx.emit(Opcode.AASTORE);
				ctx.emit(Opcode.DUP);
				ctx.emit(Opcode.ICONST_1);
				ctx.emit(Opcode.ALOAD);
				ctx.emit(restSlot);
				ctx.emit(Opcode.AASTORE);
				ctx.emit(Opcode.ASTORE);
				ctx.emit(restSlot);
			}
			ctx.emit(Opcode.ALOAD);
			ctx.emit(restSlot);
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(defun.methodref().index());
		for (int branchPos : takenBranches) {
			JvmEmitHelper.patchBranch(ctx, branchPos, ctx.code.size());
		}
	}

	/**
	 * One link of the chain: call the kernel over the temps and jump to the common end
	 * when it answered. A declined kernel leaves the stack as it found it, so the next
	 * attempt (or the scalar defun) starts from the same shape.
	 */
	private static void emitInit(JvmLispCompiler.Ctx ctx, Map<String, MethodrefConstant> ops) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get("init")).index());
	}

	private static void emitAttempt(JvmLispCompiler.Ctx ctx, Map<String, MethodrefConstant> ops, String kernelKey,
			int @org.jspecify.annotations.Nullable [] layout, int[] slots, int arity, List<Integer> takenBranches) {
		if (layout != null) {
			// The kernel's parameters in its own order: the temp of the form supplying
			// each one, or null = nil for an option the call leaves out.
			for (int i : layout) {
				if (i < 0) {
					ctx.emit(Opcode.ACONST_NULL);
				}
				else {
					ctx.emit(Opcode.ALOAD);
					ctx.emit(slots[i]);
				}
			}
		}
		else {
			loadAll(ctx, java.util.Arrays.copyOf(slots, arity));
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get(kernelKey)).index());
		// if (result != null) goto end; else fall through to the next attempt.
		ctx.emit(Opcode.DUP);
		takenBranches.add(ctx.code.size());
		ctx.emit(Opcode.IFNONNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.POP);
	}

	private static void loadAll(JvmLispCompiler.Ctx ctx, int[] slots) {
		for (int slot : slots) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(slot);
		}
	}

}
