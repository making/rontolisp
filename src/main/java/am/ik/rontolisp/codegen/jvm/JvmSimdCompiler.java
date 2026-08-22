package am.ik.rontolisp.codegen.jvm;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

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
			Map.entry(LispNames.VEC_MATVEC_INTO, 3),
			// The element-wise unary ufuncs. vec:square / vec:square-into are
			// NOT here: their spliced defuns call vec:mul / vec:mul-into, so they are
			// accelerated transitively, like mean/norm.
			Map.entry(LispNames.VEC_EXP, 1), Map.entry(LispNames.VEC_LOG, 1), Map.entry(LispNames.VEC_TANH, 1),
			Map.entry(LispNames.VEC_SIN, 1), Map.entry(LispNames.VEC_COS, 1), Map.entry(LispNames.VEC_TAN, 1),
			Map.entry(LispNames.VEC_ASIN, 1), Map.entry(LispNames.VEC_ACOS, 1), Map.entry(LispNames.VEC_ATAN, 1),
			Map.entry(LispNames.VEC_SINH, 1), Map.entry(LispNames.VEC_COSH, 1), Map.entry(LispNames.VEC_SQRT, 1),
			Map.entry(LispNames.VEC_ABS, 1), Map.entry(LispNames.VEC_NEGATIVE, 1), Map.entry(LispNames.VEC_SIGN, 1),
			Map.entry(LispNames.VEC_RECIPROCAL, 1), Map.entry(LispNames.VEC_EXP_INTO, 2),
			Map.entry(LispNames.VEC_LOG_INTO, 2), Map.entry(LispNames.VEC_TANH_INTO, 2),
			Map.entry(LispNames.VEC_SIN_INTO, 2), Map.entry(LispNames.VEC_COS_INTO, 2),
			Map.entry(LispNames.VEC_TAN_INTO, 2), Map.entry(LispNames.VEC_ASIN_INTO, 2),
			Map.entry(LispNames.VEC_ACOS_INTO, 2), Map.entry(LispNames.VEC_ATAN_INTO, 2),
			Map.entry(LispNames.VEC_SINH_INTO, 2), Map.entry(LispNames.VEC_COSH_INTO, 2),
			Map.entry(LispNames.VEC_SQRT_INTO, 2), Map.entry(LispNames.VEC_ABS_INTO, 2),
			Map.entry(LispNames.VEC_NEGATIVE_INTO, 2), Map.entry(LispNames.VEC_SIGN_INTO, 2),
			Map.entry(LispNames.VEC_RECIPROCAL_INTO, 2),
			// The comparison-select ufuncs.
			Map.entry(LispNames.VEC_MAXIMUM, 2), Map.entry(LispNames.VEC_MINIMUM, 2), Map.entry(LispNames.VEC_RELU, 1),
			Map.entry(LispNames.VEC_CLIP, 3), Map.entry(LispNames.VEC_MAXIMUM_INTO, 3),
			Map.entry(LispNames.VEC_MINIMUM_INTO, 3), Map.entry(LispNames.VEC_RELU_INTO, 2),
			Map.entry(LispNames.VEC_CLIP_INTO, 4),
			// The element-wise quotient and the four CL operator spellings, which map to
			// the same bridge methods as their named siblings.
			Map.entry(LispNames.VEC_DIV, 2), Map.entry(LispNames.VEC_DIV_INTO, 3), Map.entry(LispNames.VEC_PLUS, 2),
			Map.entry(LispNames.VEC_MINUS, 2), Map.entry(LispNames.VEC_STAR, 2), Map.entry(LispNames.VEC_SLASH, 2));

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
				LispNames.VEC_MUL_INTO, LispNames.VEC_SCALE_INTO, LispNames.VEC_MATVEC_INTO, LispNames.VEC_EXP,
				LispNames.VEC_LOG, LispNames.VEC_TANH, LispNames.VEC_SIN, LispNames.VEC_COS, LispNames.VEC_TAN,
				LispNames.VEC_ASIN, LispNames.VEC_ACOS, LispNames.VEC_ATAN, LispNames.VEC_SINH, LispNames.VEC_COSH,
				LispNames.VEC_SQRT, LispNames.VEC_ABS, LispNames.VEC_NEGATIVE, LispNames.VEC_SIGN,
				LispNames.VEC_RECIPROCAL, LispNames.VEC_EXP_INTO, LispNames.VEC_LOG_INTO, LispNames.VEC_TANH_INTO,
				LispNames.VEC_SIN_INTO, LispNames.VEC_COS_INTO, LispNames.VEC_TAN_INTO, LispNames.VEC_ASIN_INTO,
				LispNames.VEC_ACOS_INTO, LispNames.VEC_ATAN_INTO, LispNames.VEC_SINH_INTO, LispNames.VEC_COSH_INTO,
				LispNames.VEC_SQRT_INTO, LispNames.VEC_ABS_INTO, LispNames.VEC_NEGATIVE_INTO, LispNames.VEC_SIGN_INTO,
				LispNames.VEC_RECIPROCAL_INTO, LispNames.VEC_MAXIMUM, LispNames.VEC_MINIMUM, LispNames.VEC_RELU,
				LispNames.VEC_CLIP, LispNames.VEC_MAXIMUM_INTO, LispNames.VEC_MINIMUM_INTO, LispNames.VEC_RELU_INTO,
				LispNames.VEC_CLIP_INTO, LispNames.VEC_DIV, LispNames.VEC_DIV_INTO, LispNames.VEC_PLUS,
				LispNames.VEC_MINUS, LispNames.VEC_STAR, LispNames.VEC_SLASH);
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
		// An -into kernel writes its caller's destination in place and returns it. Under
		// --gpu the device may hold a resident copy of that array, keyed by identity, so
		// the call site reports the write (.kb/gpu.md, "The residency design"); the
		// allocating forms return a fresh array the device has never seen.
		Map<String, MethodrefConstant> gpuOps = ctx.gpuOps;
		if (gpuOps != null && member.endsWith("-INTO")) {
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(Objects.requireNonNull(gpuOps.get(JvmGpuRuntimeBuilder.WRITTEN)).index());
		}
	}

	/**
	 * The {@code vec:matvec} call site under {@code --gpu}: the device attempt first,
	 * over temps every branch reads, and on its {@code null} the lane kernel when
	 * {@code --simd} emitted one (which never declines) or the spliced {@code vec.lisp}
	 * defun otherwise -- the same chain {@link JvmLinalgKernelCompiler} emits for a
	 * {@code linalg:} member, here for the one device member outside that package. The
	 * library declines the FIRST sight of any matrix and every sight of one the program
	 * writes between calls ({@code .kb/gpu.md}), so the fall-through is the common path
	 * and has to be exact.
	 */
	static void compileGpuMatvec(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		Map<String, MethodrefConstant> gpu = Objects.requireNonNull(ctx.gpuOps, "gpu acceleration runtime");
		Map<String, MethodrefConstant> simd = ctx.simdOps;
		List<LispVal> args = cons.toList();
		requireArity(args.size() == 3, "vec:matvec expects 2 arguments");
		String qualified = PackageRegistry.qualify(LispNames.VEC_PKG, LispNames.VEC_MATVEC);
		JvmLispCompiler.FunctionInfo defun = ctx.functions.get(qualified);
		if (simd == null && (defun == null || defun.variadic() || defun.paramCount() != 2)) {
			// Nothing to decline to: the ordinary call path, which is what the program
			// would have run without the flag.
			JvmFunctionCallCompiler.compileDefault(qualified, cons, ctx, className);
			return;
		}
		// The bridge classes, before their method references resolve.
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(gpu.get("init")).index());
		if (simd != null) {
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(Objects.requireNonNull(simd.get("init")).index());
		}
		// Each argument form evaluated exactly once, into a temp.
		int[] slots = new int[2];
		for (int i = 0; i < 2; i++) {
			JvmExprCompiler.compileExpr(args.get(i + 1), ctx, className);
			slots[i] = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slots[i]);
		}
		// r = RontoLispGpuBridge.gpuMatvec(w, x); if (r != null) goto end;
		for (int slot : slots) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(slot);
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(gpu.get(JvmGpuRuntimeBuilder.MATVEC)).index());
		ctx.emit(Opcode.DUP);
		int taken = ctx.code.size();
		ctx.emit(Opcode.IFNONNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.POP);
		// ... else the lane kernel, or the defun.
		for (int slot : slots) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(slot);
		}
		ctx.emit(Opcode.INVOKESTATIC);
		if (simd != null) {
			ctx.emitU2(Objects.requireNonNull(simd.get(LispNames.VEC_MATVEC)).index());
		}
		else {
			ctx.emitU2(Objects.requireNonNull(defun).methodref().index());
		}
		JvmEmitHelper.patchBranch(ctx, taken, ctx.code.size());
	}

	private static void requireArity(boolean ok, String message) {
		if (!ok) {
			throw new UnsupportedOperationException(message);
		}
	}

}
