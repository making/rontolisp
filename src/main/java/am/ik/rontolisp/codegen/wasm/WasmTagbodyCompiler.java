package am.ik.rontolisp.codegen.wasm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the {@code tagbody}/{@code go} special forms. Body atoms (symbols or integers)
 * are labels; other forms are compiled for effect in order. WASM control flow is
 * structured, so the tagbody is a dispatch loop over nested segment blocks -- the
 * indirect-call dispatcher idiom: an i31-boxed program counter selects the segment via
 * {@code br_table} (segment 0 is the forms before the first label; each label starts the
 * next), execution falls through from one segment into the next, and a {@code go} sets
 * the counter and branches back to the loop to re-dispatch. Falling off the last segment
 * exits the loop and yields nil.
 *
 * <p>
 * A {@code go} resolves its tag against the innermost lexically enclosing tagbody that
 * declares it. One whose tag belongs to a tagbody OUTSIDE the nested lambda it sits in is
 * rewritten by {@code compiler/CrossLambdaExitLowering} into a throw the tagbody's
 * generated re-entry loop catches, before this compiler ever sees it
 * ({@code .kb/do-return-block.md}); only the interpreter's dynamic {@code go} into a
 * CALLER's tagbody is out of reach. A {@code go} escaping {@code unwind-protect} scopes
 * entered inside the tagbody compiles their cleanup forms inline before the branch,
 * innermost first (lite: unlike the trampolined {@code return} path, a throw from such an
 * inlined cleanup can re-enter its own scope's handler).
 */
final class WasmTagbodyCompiler {

	private WasmTagbodyCompiler() {
	}

	/**
	 * An active {@code tagbody} during compilation: the label-name to segment-index map,
	 * the i31-boxed program-counter local, the control depth of the dispatch loop (a
	 * {@code go} branches {@code wasmCtrlDepth - dispatchDepth} levels out), and the
	 * unwind-scope stack size at entry (so a {@code go} can tell which
	 * {@code unwind-protect} scopes it escapes).
	 */
	record TagbodyScope(Map<String, Integer> labelIndex, int pcSlot, int dispatchDepth, int unwindDepth) {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (ctx.asyncResume != null && WasmAwaitAnalysis.countAwaits(cons) > 0) {
			throw new UnsupportedOperationException(
					LispNames.TAGBODY + " containing await is not supported on the WASM backend");
		}
		Map<String, Integer> labelIndex = new LinkedHashMap<>();
		int segments = 0;
		for (int i = 1; i < parts.size(); i++) {
			String label = labelName(parts.get(i));
			if (label != null) {
				segments++;
				labelIndex.putIfAbsent(label, segments);
			}
		}
		int pcSlot = ctx.allocTemp();
		// pc = 0: segment 0 is the forms before the first label.
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(pcSlot);
		ctx.writer.write(Instruction.LOOP, 0x40);
		ctx.wasmCtrlDepth++;
		int dispatchDepth = ctx.wasmCtrlDepth;
		// One void block per segment, outermost = last segment; each block's end is
		// where its segment's code starts, so br_table depth k selects segment k.
		for (int k = 0; k <= segments; k++) {
			ctx.writer.write(Instruction.BLOCK, 0x40);
			ctx.wasmCtrlDepth++;
		}
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(pcSlot);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.BR_TABLE);
		ctx.writer.writeUnsignedLeb128(segments + 1);
		for (int k = 0; k <= segments; k++) {
			ctx.writer.writeUnsignedLeb128(k);
		}
		ctx.writer.writeUnsignedLeb128(0); // default; the pc is always in range
		TagbodyScope scope = new TagbodyScope(labelIndex, pcSlot, dispatchDepth, ctx.unwindScopes.size());
		ctx.tagbodyScopes.push(scope);
		ctx.writer.write(Instruction.END); // segment 0 starts here
		ctx.wasmCtrlDepth--;
		for (int i = 1; i < parts.size(); i++) {
			LispVal part = parts.get(i);
			if (labelName(part) != null) {
				ctx.writer.write(Instruction.END); // the label's segment starts here
				ctx.wasmCtrlDepth--;
			}
			else {
				// Statement position (a tagbody form's value is discarded).
				WasmExprCompiler.compileForEffect(part, ctx);
			}
		}
		ctx.tagbodyScopes.pop();
		// Falling off the last segment exits the loop (a loop has no implicit back edge).
		ctx.writer.write(Instruction.END);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

	static void compileGo(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2 || !(parts.get(1) instanceof LispSymbol)) {
			throw new IllegalArgumentException(LispNames.GO + " expects a tag: " + cons.print());
		}
		String tag = labelName(parts.get(1));
		TagbodyScope scope = null;
		for (TagbodyScope s : ctx.tagbodyScopes) {
			if (s.labelIndex().containsKey(tag)) {
				scope = s;
				break;
			}
		}
		if (scope == null) {
			// No lexically visible tagbody declares the tag. A go crossing a nested
			// lambda/flet into an ENCLOSING one never reaches here -- the shared
			// CrossLambdaExitLowering rewrote it into a %nlx-throw the tagbody's
			// re-entry loop catches. What is left is the interpreter's DYNAMIC go into
			// a caller's tagbody, which the compilers cannot express, so the jump
			// becomes a cold-path runtime signal and the library still compiles.
			WasmExprCompiler.compileExpr(new LispCons(new LispSymbol(LispNames.ERROR),
					new LispCons(new am.ik.rontolisp.LispString(LispNames.GO + " tag " + tag
							+ " has no lexically enclosing tagbody: the compilers support go within the same function only"),
							am.ik.rontolisp.LispNil.INSTANCE)),
					ctx);
			return;
		}
		// Inline the cleanups of every unwind-protect scope entered after the tagbody,
		// innermost first -- the jump leaves their protected regions.
		int escapedCount = ctx.unwindScopes.size() - scope.unwindDepth();
		int i = 0;
		for (WasmLispCompiler.UnwindScope unwindScope : ctx.unwindScopes) {
			if (i++ >= escapedCount) {
				break;
			}
			for (LispVal cleanup : unwindScope.cleanupForms()) {
				WasmExprCompiler.compileExpr(cleanup, ctx);
				ctx.writer.write(Instruction.DROP);
			}
		}
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(java.util.Objects.requireNonNull(scope.labelIndex().get(tag)));
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(scope.pcSlot());
		ctx.writer.write(Instruction.BR);
		ctx.writer.writeSignedLeb128(ctx.wasmCtrlDepth - scope.dispatchDepth());
		// No value is pushed: the branch never falls through and the abandoned
		// continuation validates stack-polymorphically, like return.
	}

	/** The label name of a tagbody body atom, or null when the element is a form. */
	static @Nullable String labelName(LispVal part) {
		if (part instanceof LispSymbol sym) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
			return qn == null ? sym.name() : qn.member();
		}
		if (part instanceof LispInteger n) {
			return Long.toString(n.value());
		}
		return null;
	}

}
