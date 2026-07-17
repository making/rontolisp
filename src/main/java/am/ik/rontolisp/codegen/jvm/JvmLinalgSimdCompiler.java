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
			// The comparison-select ufuncs. linalg:clip / linalg:relu
			// are not here: their spliced defuns compose linalg:maximum / linalg:minimum,
			// so they are accelerated transitively, like square/reciprocal.
			Map.entry(LispNames.LINALG_MAXIMUM, "laMaximum"), Map.entry(LispNames.LINALG_MINIMUM, "laMinimum"),
			// The internal CNN window unfolding pair: pure index arithmetic,
			// intercepted because the boxed defun dominates the accelerated convolution
			// runs. %-prefixed members are internal symbols, qualified with the double
			// colon (see qualifiedName).
			Map.entry(LispNames.LINALG_IM2COL, "laIm2col"), Map.entry(LispNames.LINALG_COL2IM, "laCol2im"));

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
		return switch (member) {
			case LispNames.LINALG_IM2COL -> 5;
			case LispNames.LINALG_COL2IM -> 6;
			default -> UNARY.contains(member) ? 1 : 2;
		};
	}

	/**
	 * The members whose &optional axis forms have their own bridge kernel: transpose
	 * takes an axes permutation, sum/amax/amin an axis plus keepdims, argmax/argmin an
	 * axis. A call supplying MORE arguments than {@link #arity} but at most
	 * {@code params} routes to this bridge method (missing trailing optionals padded with
	 * null = nil); on decline the surplus temps are packaged into the variadic defun's
	 * rest list.
	 */
	record Extended(String bridgeMethod, int params) {
	}

	private static final Map<String, Extended> EXTENDED = Map.of(LispNames.LINALG_TRANSPOSE,
			new Extended("laTransposeAxes", 2), LispNames.LINALG_SUM, new Extended("laSumAxis", 3),
			LispNames.LINALG_AMAX, new Extended("laAmaxAxis", 3), LispNames.LINALG_AMIN, new Extended("laAminAxis", 3),
			LispNames.LINALG_ARGMAX, new Extended("laArgmaxAxis", 2), LispNames.LINALG_ARGMIN,
			new Extended("laArgminAxis", 2));

	/** The extended (axis-form) kernel of the given member, or {@code null}. */
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

	static void compile(String member, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		Map<String, MethodrefConstant> ops = ctx.simdOps;
		if (ops == null) {
			throw new IllegalStateException("simd acceleration runtime was not emitted");
		}
		String qualified = qualifiedName(member);
		JvmLispCompiler.FunctionInfo defun = ctx.functions.get(qualified);
		List<LispVal> args = cons.toList();
		int arity = arity(member);
		int supplied = args.size() - 1;
		Extended ext = supplied > arity ? EXTENDED.get(member) : null;
		boolean extendedCall = ext != null && supplied <= ext.params();
		if (defun == null || (supplied != arity && !extendedCall)
				|| (defun.variadic() ? defun.paramCount() - 1 : defun.paramCount()) != arity
				|| (extendedCall && !defun.variadic())) {
			// No spliced linalg.lisp to fall back to, a call with more arguments than
			// any kernel handles, or a defun whose required count no longer matches:
			// the ordinary direct-call path handles all three.
			JvmFunctionCallCompiler.compileDefault(qualified, cons, ctx, className);
			return;
		}
		// The bridge class must be defined before its method reference resolves.
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get("init")).index());
		// Evaluate each argument exactly once, into a temp both branches read.
		int[] slots = new int[supplied];
		for (int i = 0; i < supplied; i++) {
			JvmExprCompiler.compileExpr(args.get(i + 1), ctx, className);
			slots[i] = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slots[i]);
		}
		loadAll(ctx, slots);
		if (extendedCall && ext != null) {
			// A missing trailing &optional (e.g. keepdims) is padded with null = nil.
			for (int i = supplied; i < ext.params(); i++) {
				ctx.emit(Opcode.ACONST_NULL);
			}
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get(extendedCall ? extendedKey(member) : qualified)).index());
		// if (result != null) goto end; else run the scalar defun over the same temps.
		ctx.emit(Opcode.DUP);
		int branchPos = ctx.code.size();
		ctx.emit(Opcode.IFNONNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.POP);
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
		JvmEmitHelper.patchBranch(ctx, branchPos, ctx.code.size());
	}

	private static void loadAll(JvmLispCompiler.Ctx ctx, int[] slots) {
		for (int slot : slots) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(slot);
		}
	}

}
