package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
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
 * Compiles the accelerated {@code linalg:} kernels to calls into the embedded
 * {@link JvmSimdVectorTemplate bridge}, the {@code linalg:} sibling of
 * {@link JvmSimdCompiler}. Only wired in when the {@code --simd} flag emitted the runtime
 * ({@link JvmLispCompiler.Ctx#simdOps} is non-null); otherwise the qualified call falls
 * through to the ordinary spliced {@code linalg.lisp} defun.
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
final class JvmLinalgSimdCompiler {

	private JvmLinalgSimdCompiler() {
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
			// The element-wise unary ufuncs (todo 109). linalg:square / linalg:reciprocal
			// are not here: their spliced defuns call linalg:mul / linalg:div, so they
			// are accelerated transitively, like mean/matmul.
			Map.entry(LispNames.LINALG_EXP, "laExp"), Map.entry(LispNames.LINALG_LOG, "laLog"),
			Map.entry(LispNames.LINALG_TANH, "laTanh"), Map.entry(LispNames.LINALG_SIN, "laSin"),
			Map.entry(LispNames.LINALG_COS, "laCos"), Map.entry(LispNames.LINALG_TAN, "laTan"),
			Map.entry(LispNames.LINALG_ASIN, "laAsin"), Map.entry(LispNames.LINALG_ACOS, "laAcos"),
			Map.entry(LispNames.LINALG_ATAN, "laAtan"), Map.entry(LispNames.LINALG_SINH, "laSinh"),
			Map.entry(LispNames.LINALG_COSH, "laCosh"), Map.entry(LispNames.LINALG_SQRT, "laSqrt"),
			Map.entry(LispNames.LINALG_ABS, "laAbs"), Map.entry(LispNames.LINALG_NEGATIVE, "laNegative"),
			Map.entry(LispNames.LINALG_SIGN, "laSign"));

	/** The unary members; everything else takes two arguments. */
	private static final List<String> UNARY = List.of(LispNames.LINALG_SUM, LispNames.LINALG_NORM,
			LispNames.LINALG_AMAX, LispNames.LINALG_AMIN, LispNames.LINALG_ARGMAX, LispNames.LINALG_ARGMIN,
			LispNames.LINALG_TRACE, LispNames.LINALG_TRANSPOSE, LispNames.LINALG_EXP, LispNames.LINALG_LOG,
			LispNames.LINALG_TANH, LispNames.LINALG_SIN, LispNames.LINALG_COS, LispNames.LINALG_TAN,
			LispNames.LINALG_ASIN, LispNames.LINALG_ACOS, LispNames.LINALG_ATAN, LispNames.LINALG_SINH,
			LispNames.LINALG_COSH, LispNames.LINALG_SQRT, LispNames.LINALG_ABS, LispNames.LINALG_NEGATIVE,
			LispNames.LINALG_SIGN);

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
		return UNARY.contains(member) ? 1 : 2;
	}

	static void compile(String member, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		Map<String, MethodrefConstant> ops = ctx.simdOps;
		if (ops == null) {
			throw new IllegalStateException("simd acceleration runtime was not emitted");
		}
		String qualified = PackageRegistry.qualify(LispNames.LINALG_PKG, member);
		JvmLispCompiler.FunctionInfo defun = ctx.functions.get(qualified);
		List<LispVal> args = cons.toList();
		int arity = arity(member);
		if (defun == null || args.size() != arity + 1) {
			// No spliced linalg.lisp to fall back to (or a bad arity): let the ordinary
			// direct-call path raise the usual compile error.
			JvmFunctionCallCompiler.compileDefault(qualified, cons, ctx, className);
			return;
		}
		// The bridge class must be defined before its method reference resolves.
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get("init")).index());
		// Evaluate each argument exactly once, into a temp both branches read.
		int[] slots = new int[arity];
		for (int i = 0; i < arity; i++) {
			JvmExprCompiler.compileExpr(args.get(i + 1), ctx, className);
			slots[i] = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slots[i]);
		}
		loadAll(ctx, slots);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get(qualified)).index());
		// if (result != null) goto end; else run the scalar defun over the same temps.
		ctx.emit(Opcode.DUP);
		int branchPos = ctx.code.size();
		ctx.emit(Opcode.IFNONNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.POP);
		loadAll(ctx, slots);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(defun.methodref().index());
		JvmEmitHelper.patchBranch(ctx, branchPos, ctx.code.size());
	}

	private static void loadAll(JvmLispCompiler.Ctx ctx, int[] slots) {
		for (int slot : slots) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(slot);
		}
	}

}
