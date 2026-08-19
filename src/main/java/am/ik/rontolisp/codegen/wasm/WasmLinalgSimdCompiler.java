package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.LinalgKernelCallLayout;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the accelerated {@code linalg:} kernels to calls into the emitted v128 runtime
 * helpers ({@link WasmLinalgSimdRuntimeBuilder}), the wasm-GC counterpart of
 * {@code JvmLinalgSimdCompiler} and the {@code linalg:} sibling of
 * {@link WasmVecSimdCompiler}. Only wired in when {@code --simd} emitted the helpers
 * ({@link WasmLispCompiler.Ctx#simd}); otherwise the qualified call falls through to the
 * ordinary spliced {@code linalg.lisp} defun.
 *
 * <h2>Why this call site is not simply a call</h2>
 *
 * {@code vec:} accepts packed float arrays and nothing else, so
 * {@link WasmVecSimdCompiler} emits a bare {@code call}. {@code linalg:} also accepts
 * general (boxed) arrays, mixed widths, a scalar operand on either side, plain numbers,
 * and mismatched shapes (a specific {@code error}). Each kernel is therefore PARTIAL: it
 * returns a null reference for an input it does not handle, and this compiler emits
 *
 * <pre>
 *   a = &lt;arg1&gt;; b = &lt;arg2&gt;;      // evaluated ONCE, into locals
 *   r = call $_la_add(a, b);
 *   if (ref.is_null r) r = call $linalg:add(null, a, b);
 *   r
 * </pre>
 *
 * The locals are what make the fallback safe: recompiling the argument forms would
 * evaluate their side effects twice. Compiled nil IS a null reference, but none of the
 * intercepted members ever returns nil -- {@code linalg:array-equal}, which does, is
 * deliberately not intercepted -- so a null result is an unambiguous "declined".
 *
 * <p>
 * {@code mean}, {@code matmul}, {@code flatten} and {@code solve} are accelerated
 * transitively through the {@code sum} / {@code dot} / {@code reshape} their spliced
 * bodies call. Nor is {@code #'linalg:dot}: a first-class function value still names the
 * scalar defun, exactly as with {@code vec:} and on the JVM.
 *
 * <p>
 * {@code WasmTreeShaker} needs no new root: the emitted call site has ordinary
 * {@code call} edges to both the kernel and the scalar defun, so the defun stays
 * reachable wherever a kernel can decline.
 */
final class WasmLinalgSimdCompiler {

	private WasmLinalgSimdCompiler() {
	}

	/** The accelerated members, mapped to their runtime-function offset. */
	private static final Map<String, Integer> KERNELS = Map.ofEntries(
			Map.entry(LispNames.LINALG_ADD, WasmLinalgSimdRuntimeBuilder.ADD),
			Map.entry(LispNames.LINALG_SUB, WasmLinalgSimdRuntimeBuilder.SUB),
			Map.entry(LispNames.LINALG_MUL, WasmLinalgSimdRuntimeBuilder.MUL),
			Map.entry(LispNames.LINALG_DIV, WasmLinalgSimdRuntimeBuilder.DIV),
			Map.entry(LispNames.LINALG_SUM, WasmLinalgSimdRuntimeBuilder.SUM),
			Map.entry(LispNames.LINALG_NORM, WasmLinalgSimdRuntimeBuilder.NORM),
			Map.entry(LispNames.LINALG_AMAX, WasmLinalgSimdRuntimeBuilder.AMAX),
			Map.entry(LispNames.LINALG_AMIN, WasmLinalgSimdRuntimeBuilder.AMIN),
			Map.entry(LispNames.LINALG_ARGMAX, WasmLinalgSimdRuntimeBuilder.ARGMAX),
			Map.entry(LispNames.LINALG_ARGMIN, WasmLinalgSimdRuntimeBuilder.ARGMIN),
			Map.entry(LispNames.LINALG_TRACE, WasmLinalgSimdRuntimeBuilder.TRACE),
			Map.entry(LispNames.LINALG_TRANSPOSE, WasmLinalgSimdRuntimeBuilder.TRANSPOSE),
			Map.entry(LispNames.LINALG_RESHAPE, WasmLinalgSimdRuntimeBuilder.RESHAPE),
			Map.entry(LispNames.LINALG_DOT, WasmLinalgSimdRuntimeBuilder.DOT),
			Map.entry(LispNames.LINALG_OUTER, WasmLinalgSimdRuntimeBuilder.OUTER),
			// The element-wise unary ufuncs. linalg:square / linalg:reciprocal are not
			// here: their spliced defuns call linalg:mul / linalg:div, so they are
			// accelerated transitively, like mean/matmul.
			Map.entry(LispNames.LINALG_EXP, WasmLinalgSimdRuntimeBuilder.EXP),
			Map.entry(LispNames.LINALG_LOG, WasmLinalgSimdRuntimeBuilder.LOG),
			Map.entry(LispNames.LINALG_TANH, WasmLinalgSimdRuntimeBuilder.TANH),
			Map.entry(LispNames.LINALG_SIN, WasmLinalgSimdRuntimeBuilder.SIN),
			Map.entry(LispNames.LINALG_COS, WasmLinalgSimdRuntimeBuilder.COS),
			Map.entry(LispNames.LINALG_TAN, WasmLinalgSimdRuntimeBuilder.TAN),
			Map.entry(LispNames.LINALG_ASIN, WasmLinalgSimdRuntimeBuilder.ASIN),
			Map.entry(LispNames.LINALG_ACOS, WasmLinalgSimdRuntimeBuilder.ACOS),
			Map.entry(LispNames.LINALG_ATAN, WasmLinalgSimdRuntimeBuilder.ATAN),
			Map.entry(LispNames.LINALG_SINH, WasmLinalgSimdRuntimeBuilder.SINH),
			Map.entry(LispNames.LINALG_COSH, WasmLinalgSimdRuntimeBuilder.COSH),
			Map.entry(LispNames.LINALG_SQRT, WasmLinalgSimdRuntimeBuilder.SQRT),
			Map.entry(LispNames.LINALG_ABS, WasmLinalgSimdRuntimeBuilder.ABS),
			Map.entry(LispNames.LINALG_NEGATIVE, WasmLinalgSimdRuntimeBuilder.NEGATIVE),
			Map.entry(LispNames.LINALG_SIGN, WasmLinalgSimdRuntimeBuilder.SIGN),
			// The comparison-select ufuncs. linalg:clip / linalg:relu are not here:
			// their spliced defuns compose linalg:maximum / linalg:minimum, so they are
			// accelerated transitively, like square/reciprocal.
			Map.entry(LispNames.LINALG_MAXIMUM, WasmLinalgSimdRuntimeBuilder.MAXIMUM),
			Map.entry(LispNames.LINALG_MINIMUM, WasmLinalgSimdRuntimeBuilder.MINIMUM),
			// The internal CNN window unfolding pair: %-prefixed members are internal
			// symbols, qualified with the double colon (see qualifiedName).
			Map.entry(LispNames.LINALG_IM2COL, WasmLinalgSimdRuntimeBuilder.IM2COL),
			Map.entry(LispNames.LINALG_COL2IM, WasmLinalgSimdRuntimeBuilder.COL2IM));

	/** The unary members; everything else takes two arguments. */
	private static final List<String> UNARY = List.of(LispNames.LINALG_SUM, LispNames.LINALG_NORM,
			LispNames.LINALG_AMAX, LispNames.LINALG_AMIN, LispNames.LINALG_ARGMAX, LispNames.LINALG_ARGMIN,
			LispNames.LINALG_TRACE, LispNames.LINALG_TRANSPOSE, LispNames.LINALG_EXP, LispNames.LINALG_LOG,
			LispNames.LINALG_TANH, LispNames.LINALG_SIN, LispNames.LINALG_COS, LispNames.LINALG_TAN,
			LispNames.LINALG_ASIN, LispNames.LINALG_ACOS, LispNames.LINALG_ATAN, LispNames.LINALG_SINH,
			LispNames.LINALG_COSH, LispNames.LINALG_SQRT, LispNames.LINALG_ABS, LispNames.LINALG_NEGATIVE,
			LispNames.LINALG_SIGN);

	/** Returns whether the given qualified name is a kernel this compiler accelerates. */
	static boolean handles(String qualifiedName) {
		return KERNELS.containsKey(member(qualifiedName));
	}

	/** The accelerated member names, in a stable order (the {@code --simd} emit gate). */
	static List<String> members() {
		List<String> sorted = new ArrayList<>(KERNELS.keySet());
		sorted.sort(String::compareTo);
		return sorted;
	}

	private static String member(String qualifiedName) {
		if (!qualifiedName.startsWith(LispNames.LINALG_PKG + ":")) {
			return "";
		}
		return qualifiedName.substring(qualifiedName.lastIndexOf(':') + 1);
	}

	private static int arity(String member) {
		return switch (member) {
			case LispNames.LINALG_IM2COL -> 5;
			case LispNames.LINALG_COL2IM -> 6;
			default -> UNARY.contains(member) ? 1 : 2;
		};
	}

	/**
	 * The members whose option forms have their own kernel: transpose takes a positional
	 * axes permutation, sum/amax/amin the {@code :axis} / {@code :keepdims} keywords,
	 * argmax/argmin {@code :axis}. A call whose argument forms fit the member's
	 * {@link LinalgKernelCallLayout.Extended shape} routes to this kernel (an option not
	 * supplied is padded with a null ref = nil); on decline the surplus locals -- keyword
	 * literals included -- are linked into the variadic defun's rest list.
	 */
	private record Extended(int offset, int params) {
	}

	private static final Map<String, Extended> EXTENDED = Map.of(LispNames.LINALG_TRANSPOSE,
			new Extended(WasmLinalgSimdRuntimeBuilder.TRANSPOSE_AXES, 2), LispNames.LINALG_SUM,
			new Extended(WasmLinalgSimdRuntimeBuilder.SUM_AXIS, 3), LispNames.LINALG_AMAX,
			new Extended(WasmLinalgSimdRuntimeBuilder.AMAX_AXIS, 3), LispNames.LINALG_AMIN,
			new Extended(WasmLinalgSimdRuntimeBuilder.AMIN_AXIS, 3), LispNames.LINALG_ARGMAX,
			new Extended(WasmLinalgSimdRuntimeBuilder.ARGMAX_AXIS, 2), LispNames.LINALG_ARGMIN,
			new Extended(WasmLinalgSimdRuntimeBuilder.ARGMIN_AXIS, 2));

	/**
	 * The member's canonical qualified spelling: a {@code %}-prefixed member is an
	 * internal symbol and carries the double colon ({@code linalg::%la-im2col}), which is
	 * how the spliced defun is keyed in {@code ctx.functions}.
	 */
	private static String qualifiedName(String member) {
		return member.startsWith("%") ? PackageRegistry.qualifyInternal(LispNames.LINALG_PKG, member)
				: PackageRegistry.qualify(LispNames.LINALG_PKG, member);
	}

	static void compile(String qualifiedName, LispCons cons, WasmLispCompiler.Ctx ctx) {
		String member = member(qualifiedName);
		int arity = arity(member);
		List<LispVal> args = cons.toList();
		int supplied = args.size() - 1;
		Extended ext = supplied > arity ? EXTENDED.get(member) : null;
		LinalgKernelCallLayout.Extended shape = ext != null ? LinalgKernelCallLayout.extended(member) : null;
		int[] layout = shape != null ? LinalgKernelCallLayout.layout(shape, arity, args.subList(1, args.size())) : null;
		boolean extendedCall = layout != null;
		int offset = ext != null && extendedCall ? ext.offset() : Objects.requireNonNull(KERNELS.get(member));
		String qualified = qualifiedName(member);
		WasmLispCompiler.WasmFunctionInfo defun = ctx.functions.get(qualified);
		if (defun == null || (supplied != arity && !extendedCall)
				|| (defun.variadic() ? defun.paramCount() - 1 : defun.paramCount()) != arity
				|| (extendedCall && !defun.variadic())) {
			// No spliced linalg.lisp to fall back to, a call whose option forms no kernel
			// handles, or a defun whose required count no longer matches: the ordinary
			// direct-call path handles all three.
			WasmFunctionCallCompiler.compileDefault(qualified, cons, ctx);
			return;
		}
		// Evaluate each argument exactly once, into a local both branches read.
		int[] slots = new int[supplied];
		for (int i = 0; i < supplied; i++) {
			WasmExprCompiler.compileExpr(args.get(i + 1), ctx);
			slots[i] = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slots[i]);
		}
		int result = ctx.allocTemp();
		if (layout != null) {
			// The kernel's parameters in its own order: the local of the form supplying
			// each one, or a null ref = nil for an option the call leaves out.
			for (int i : layout) {
				if (i < 0) {
					ctx.writer.write(Instruction.REF_NULL);
					ctx.writer.writeHeapType(Type.EQ.code());
				}
				else {
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writer.writeUnsignedLeb128(slots[i]);
				}
			}
		}
		else {
			loadAll(ctx, slots);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.linalgFuncBase() + offset);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(result);
		// A null result means the kernel declined: run the scalar defun over the same
		// locals. (The defun's leading parameter is the ignored closure environment.)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(result);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		for (int i = 0; i < arity; i++) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slots[i]);
		}
		if (defun.variadic()) {
			// A defun with an &optional/&rest lambda list takes a trailing rest
			// parameter: the surplus locals are linked into a cons list through the
			// result local (an empty rest list is a null reference).
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(result);
			for (int k = supplied - 1; k >= arity; k--) {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slots[k]);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(result);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(result);
			}
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(result);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(defun.funcIndex());
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(result);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(result);
	}

	private static void loadAll(WasmLispCompiler.Ctx ctx, int[] slots) {
		for (int slot : slots) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
		}
	}

}
