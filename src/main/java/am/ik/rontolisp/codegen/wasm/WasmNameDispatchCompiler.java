package am.ik.rontolisp.codegen.wasm;

import java.util.Arrays;
import java.util.Comparator;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.NameDispatch;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import org.jspecify.annotations.Nullable;

/**
 * A name dispatch ({@link NameDispatch}) as a search over string-table offsets: the
 * name's canonical offset -- what {@code %symbol-is} compares, and {@code eq} on a symbol
 * -- read ONCE into an i64 scratch local (a non-symbol goes straight to the miss), a
 * binary search over the arms' offsets, and at the bottom a run of at most
 * {@link #LEAF_ARMS} offset compares, each a {@code local.get} and a constant. Every
 * {@code %symbol-is} of the chain repeated the {@code ref.test}, the {@code ref.cast} and
 * the {@code struct.get} of the same parameter.
 *
 * <pre>
 * block $out (result eqref)          ;; void for effect
 *   block $miss
 *     name ref.test $str  i32.eqz  br_if $miss
 *     name ref.cast $str  struct.get $str 0  i64.extend_i32_u  local.set $k
 *     ;; a split:  local.get $k  i64.const PIVOT  i64.lt_s  if ... else ... end
 *     ;; a leaf:   local.get $k  i32.const OFF  i64.extend_i32_u  i64.eq  if ARM br $out end
 *   end
 *   MISS
 * end
 * </pre>
 *
 * A leaf compares an {@code i32.const}: that constant is what keeps the name's string
 * (and its intern row) alive through the tree shaker, which probes a surviving body's
 * {@code i32.const}s -- a runtime {@code intern} of the name must find the canonical
 * offset the arm tests. A split's pivot is only a boundary and needs none.
 */
final class WasmNameDispatchCompiler {

	/**
	 * The fewest arms the search is emitted for: below, the chain's own tests are smaller
	 * than the key's set-up.
	 */
	static final int MIN_ARMS = 2;

	/** The most offset compares a leaf of the search runs. */
	static final int LEAF_ARMS = 16;

	private WasmNameDispatchCompiler() {
	}

	/**
	 * The dispatch {@code cons} heads when this compiler takes it, else null: not in
	 * state-machine mode (an async body routes every {@code if} through its resume
	 * states), and at least {@link #MIN_ARMS} arms.
	 * @param cons an {@code if} form
	 * @param ctx the function context
	 * @return the dispatch to compile, or null
	 */
	static @Nullable NameDispatch match(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (ctx.asyncResume != null) {
			return null;
		}
		NameDispatch d = NameDispatch.match(cons);
		return d != null && d.names().size() >= MIN_ARMS ? d : null;
	}

	/**
	 * Emits the dispatch.
	 * @param d the dispatch
	 * @param ctx the function context, its tail marker already consumed
	 * @param tail whether the dispatch's value is the function's result (its arms and
	 * miss are in tail position)
	 * @param value whether the value is wanted; for effect, every arm and the miss
	 * compile for effect and the blocks are void
	 */
	static void compile(NameDispatch d, WasmLispCompiler.Ctx ctx, boolean tail, boolean value) {
		int n = d.names().size();
		Integer[] order = new Integer[n];
		int[] offsets = new int[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
			offsets[i] = ctx.stringTable.addString(d.names().get(i)).offset();
		}
		Arrays.sort(order, Comparator.comparingInt(i -> offsets[i]));
		long[] keys = new long[n];
		for (int i = 0; i < n; i++) {
			keys[i] = offsets[order[i]];
		}
		int savedI64Locals = ctx.nextI64Local;
		int key = ctx.allocI64Temp();
		blockOpen(ctx, value);
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.wasmCtrlDepth++;
		WasmExprCompiler.compileExpr(d.subject(), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.BR_IF);
		ctx.writer.writeUnsignedLeb128(0);
		WasmExprCompiler.compileExpr(d.subject(), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.writer.write(Instruction.I64_EXTEND_U_I32);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writeI64LocalIndex(key);
		emitNode(NameDispatch.searchTree(keys, LEAF_ARMS), d, order, offsets, key, ctx, tail, value, 0);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
		emitArm(d.miss(), ctx, tail, value);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
		ctx.nextI64Local = savedI64Locals;
	}

	private static void blockOpen(WasmLispCompiler.Ctx ctx, boolean value) {
		ctx.writer.write(Instruction.BLOCK);
		if (value) {
			ctx.writer.writeRefType(true, Type.EQ.code());
		}
		else {
			ctx.writer.write(0x40);
		}
		ctx.wasmCtrlDepth++;
	}

	/**
	 * One node of the search, {@code depth} void {@code if}s inside the miss block: a
	 * matching leaf compare runs its arm and branches out of the dispatch, past the miss
	 * the search falls through to.
	 */
	private static void emitNode(NameDispatch.Node node, NameDispatch d, Integer[] order, int[] offsets, int key,
			WasmLispCompiler.Ctx ctx, boolean tail, boolean value, int depth) {
		switch (node) {
			case NameDispatch.Split split -> {
				getKey(ctx, key);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(split.pivot());
				ctx.writer.write(Instruction.I64_LT_S);
				ctx.writer.write(Instruction.IF, 0x40);
				ctx.wasmCtrlDepth++;
				emitNode(split.below(), d, order, offsets, key, ctx, tail, value, depth + 1);
				ctx.writer.write(Instruction.ELSE);
				emitNode(split.atOrAbove(), d, order, offsets, key, ctx, tail, value, depth + 1);
				ctx.wasmCtrlDepth--;
				ctx.writer.write(Instruction.END);
			}
			case NameDispatch.Leaf leaf -> {
				for (int p = leaf.from(); p < leaf.to(); p++) {
					int arm = order[p];
					getKey(ctx, key);
					ctx.writer.write(Instruction.I32_CONST);
					ctx.writer.writeSignedLeb128(offsets[arm]);
					ctx.writer.write(Instruction.I64_EXTEND_U_I32);
					ctx.writer.write(Instruction.I64_EQ);
					ctx.writer.write(Instruction.IF, 0x40);
					ctx.wasmCtrlDepth++;
					emitArm(d.arms().get(arm), ctx, tail, value);
					// Out of this if, the splits above it and the miss block.
					ctx.writer.write(Instruction.BR);
					ctx.writer.writeUnsignedLeb128(depth + 2);
					ctx.wasmCtrlDepth--;
					ctx.writer.write(Instruction.END);
				}
			}
		}
	}

	private static void emitArm(LispVal form, WasmLispCompiler.Ctx ctx, boolean tail, boolean value) {
		if (value) {
			ctx.tailPosition = tail;
			WasmExprCompiler.compileExpr(form, ctx);
		}
		else {
			WasmExprCompiler.compileForEffect(form, ctx);
		}
	}

	private static void getKey(WasmLispCompiler.Ctx ctx, int key) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(key);
	}

}
