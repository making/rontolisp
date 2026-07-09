package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
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
			Map.entry(LispNames.LINALG_OUTER, WasmLinalgSimdRuntimeBuilder.OUTER));

	/** The unary members; everything else takes two arguments. */
	private static final List<String> UNARY = List.of(LispNames.LINALG_SUM, LispNames.LINALG_NORM,
			LispNames.LINALG_AMAX, LispNames.LINALG_AMIN, LispNames.LINALG_ARGMAX, LispNames.LINALG_ARGMIN,
			LispNames.LINALG_TRACE, LispNames.LINALG_TRANSPOSE);

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
		return UNARY.contains(member) ? 1 : 2;
	}

	static void compile(String qualifiedName, LispCons cons, WasmLispCompiler.Ctx ctx) {
		String member = member(qualifiedName);
		int offset = Objects.requireNonNull(KERNELS.get(member));
		int arity = arity(member);
		List<LispVal> args = cons.toList();
		String qualified = PackageRegistry.qualify(LispNames.LINALG_PKG, member);
		WasmLispCompiler.WasmFunctionInfo defun = ctx.functions.get(qualified);
		if (defun == null || args.size() != arity + 1) {
			// No spliced linalg.lisp to fall back to (or a bad arity): let the ordinary
			// direct-call path raise the usual compile error.
			WasmFunctionCallCompiler.compileDefault(qualified, cons, ctx);
			return;
		}
		// Evaluate each argument exactly once, into a local both branches read.
		int[] slots = new int[arity];
		for (int i = 0; i < arity; i++) {
			WasmExprCompiler.compileExpr(args.get(i + 1), ctx);
			slots[i] = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(slots[i]);
		}
		int result = ctx.allocTemp();
		loadAll(ctx, slots);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.linalgFuncBase() + offset);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(result);
		// A null result means the kernel declined: run the scalar defun over the same
		// locals. (The defun's leading parameter is the ignored closure environment.)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(result);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		loadAll(ctx, slots);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(defun.funcIndex());
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(result);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(result);
	}

	private static void loadAll(WasmLispCompiler.Ctx ctx, int[] slots) {
		for (int slot : slots) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
		}
	}

}
