package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Fuses a nested integer arithmetic/bitwise expression tree ({@code + - * mod rem logand
 * logior logxor lognot ash}, plus {@code 1+}/{@code 1-}) into ONE unboxed evaluation: the
 * leaves are evaluated once into scratch locals, the interior stays raw {@code i64} on
 * the wasm stack, and only the root result boxes through {@code _int_new}. Without this,
 * every interior operation of {@code (logand (+ a b) mask)} pays its own generic-helper
 * call plus an unbox/re-box round trip -- and outside the i31 range each re-box ALLOCATES
 * a {@code TYPE_BIGNUM}, which is what dominated the SHA-256 profile.
 *
 * <p>
 * Exactness is preserved by construction: the fast path is guarded per leaf ({@code
 * _fx_val} bails on anything but an i31 / {@code TYPE_BIGNUM}) and per checked operation
 * ({@code _fx_add/_fx_sub/_fx_mul/_fx_ash} bail on i64 overflow,
 * {@link WasmFxRuntimeBuilder}); any bail branches to a fallback that recomputes the
 * whole tree from the SAME leaf locals through the generic {@code _rat_*}/{@code _big_*}
 * helpers -- identical results (including bignum promotion and the narrowest-tier
 * invariant, {@code .kb/wasm-bignum.md}), the leaves' side effects run exactly once, and
 * a float or ratio reaching a fused site simply always takes the fallback. A node whose
 * immediate argument is a literal double keeps the existing f64 path (it becomes an
 * unfused leaf), matching {@code WasmLispCompiler.hasDoubleLiteral}.
 *
 * <p>
 * Fusion triggers only for trees with at least two fusable operations (a single operation
 * is already one generic call today) and stays out of async resume bodies (their
 * spine/hoist analysis owns the argument shapes there).
 */
final class WasmIntFusionCompiler {

	/**
	 * Trees past these bounds fall back to the generic per-op path: the fused site emits
	 * the tree twice (fast + fallback), and emitted body size must stay bounded
	 * (.kb/wasm-function-body-size.md).
	 */
	private static final int MAX_EXPR_LEAVES = 32;

	private static final int MAX_OPS = 64;

	private WasmIntFusionCompiler() {
	}

	/**
	 * Whether this module emits the two speed-for-size trades this file implements --
	 * fusion itself (a fused site emits its tree TWICE, once raw and once through the
	 * generic helpers) and the unboxed dual-representation locals that feed it
	 * ({@link WasmLetCompiler}'s eligibility scan asks here too). {@code false} only
	 * under {@code --optimize=size}.
	 *
	 * <p>
	 * <strong>One switch for both, deliberately.</strong> An unboxed local's whole payoff
	 * is being read raw inside a fused tree and stored raw from one; with fusion off
	 * every assignment would bail into the boxed shadow and every read would go through
	 * {@code _ub_read}. Measured, that half-configuration is dominated on BOTH axes --
	 * larger than declining both, slower than keeping both
	 * ({@code .kb/optimize-dead-code-elimination.md} has the four-way table), so it is a
	 * configuration nobody can want and it gets no spelling.
	 * @param ctx the compilation context
	 * @return {@code true} when the trades are emitted
	 */
	static boolean speedTradesEnabled(WasmLispCompiler.Ctx ctx) {
		return !ctx.optimize.prefersSizeOverSpeed();
	}

	private sealed interface Node permits OpNode, ConstLeaf, ExprLeaf, ArefLeaf, RawLeaf {

	}

	/**
	 * An unboxed (dual-representation) local variable, todo 194 stage 3: {@code
	 * i64Slot} holds the raw value and {@code shadowSlot} (an ordinary eqref local) holds
	 * the module's raw-local SENTINEL (a private TYPE_CELL instance,
	 * {@code Ctx.rawSentinelGlobalIndex}) while the raw value is authoritative. An
	 * assignment whose fused fast path succeeds stores raw and sets the shadow to the
	 * sentinel; a bailing assignment (overflow promotion, a non-integer value) stores the
	 * boxed result into the shadow instead -- so "shadow != sentinel" means "use the
	 * shadow, whatever it is, INCLUDING nil/null" (null cannot be the marker: nil IS
	 * null, and a local assigned nil must read back as nil, not as the stale raw slot).
	 * Registered per eligible {@code let} binding by {@link WasmLetCompiler}.
	 *
	 * <p>
	 * {@code counted} marks the OTHER flavour: a counted loop's induction variable
	 * ({@link WasmDotimesCompiler}), which has NO shadow slot at all
	 * ({@code shadowSlot < 0}). Its bound is a literal, it starts at zero and the only
	 * assignment anywhere is the loop's own {@code +1} step, so the i64 slot is
	 * authoritative at every point and its value provably stays inside the i31 fixnum
	 * range -- every guard, every bail and every {@code _ub_read} the dual representation
	 * needs is statically dead. That is what makes it a size AND speed win at every
	 * optimize level, where the dual representation is a speed-for-size trade
	 * {@code --optimize=size} declines.
	 */
	record RawLocal(int i64Slot, int shadowSlot, boolean counted) {

		RawLocal {
			if (counted != (shadowSlot < 0)) {
				throw new IllegalArgumentException("a counted raw local has no shadow slot, and only a counted one");
			}
		}

		/** The dual-representation flavour: an i64 slot plus its boxed shadow. */
		static RawLocal dual(int i64Slot, int shadowSlot) {
			return new RawLocal(i64Slot, shadowSlot, false);
		}

		/** The counted-loop flavour: an i64 slot that is always authoritative. */
		static RawLocal counted(int i64Slot) {
			return new RawLocal(i64Slot, -1, true);
		}
	}

	private record OpNode(String op, List<Node> args) implements Node {
	}

	private record ConstLeaf(long value) implements Node {
	}

	private static final class ExprLeaf implements Node {

		final LispVal expr;

		int slot = -1;

		int i64Slot = -1;

		ExprLeaf(LispVal expr) {
			this.expr = expr;
		}

	}

	/**
	 * A rank-1 {@code (aref a i)} leaf: the array and index evaluate once into scratch
	 * locals, and the fast path reads the element RAW when the array is a packed integer
	 * vector (todo 194 stage 2) -- no {@code _int_new} box, which for an out-of-i31
	 * {@code (unsigned-byte 32)} element deletes a {@code TYPE_BIGNUM} allocation per
	 * read. Any other array shape (or a non-i31 index) bails to the fallback, which
	 * reruns the ordinary aref dispatch from the SAME locals.
	 */
	private static final class ArefLeaf implements Node {

		final LispVal arrayExpr;

		final LispVal indexExpr;

		int arrSlot = -1;

		int idxSlot = -1;

		int i64Slot = -1;

		ArefLeaf(LispVal arrayExpr, LispVal indexExpr) {
			this.arrayExpr = arrayExpr;
			this.indexExpr = indexExpr;
		}

	}

	/**
	 * A read of an unboxed local ({@link RawLocal}) inside a fused tree. Registered as a
	 * leaf so the leaf-evaluation loop SNAPSHOTS the pair at the read's source position
	 * (a later leaf's side effect may reassign the local); the unbox hoist then resolves
	 * the snapshot into a single i64 ({@code snapI64}): the raw value when the shadow is
	 * null, the shadow's guarded unbox otherwise (bailing to the fallback on a
	 * non-i64-integer shadow).
	 */
	private static final class RawLeaf implements Node {

		final RawLocal src;

		int snapI64 = -1;

		int snapShadow = -1;

		RawLeaf(RawLocal src) {
			this.src = src;
		}

	}

	/**
	 * Per-fused-site classification state: the registered leaves, plus what makes
	 * raw-local reads shareable -- a local that NO leaf of this site can reassign (no
	 * setq/setf of the name anywhere in the site's expression) snapshots once and is read
	 * at every occurrence, instead of paying a snapshot + resolve per occurrence.
	 */
	private static final class Site {

		final List<Node> leaves = new ArrayList<>();

		final java.util.Map<String, RawLeaf> sharedRawLeaves = new java.util.HashMap<>();

		final java.util.Set<String> assignedNames = new java.util.HashSet<>();

		Site(LispVal expr) {
			collectAssignedNames(expr, this.assignedNames);
		}

		private static void collectAssignedNames(LispVal form, java.util.Set<String> out) {
			if (!(form instanceof LispCons cons)) {
				return;
			}
			if (cons.car() instanceof LispSymbol head && cons.isProperList()
					&& (LispNames.SETQ.equals(head.name()) || LispNames.SETF.equals(head.name()))) {
				List<LispVal> parts = cons.toList();
				for (int i = 1; i + 1 < parts.size(); i += 2) {
					if (parts.get(i) instanceof LispSymbol target) {
						out.add(target.name());
					}
				}
			}
			LispVal cur = cons;
			while (cur instanceof LispCons cell) {
				collectAssignedNames(cell.car(), out);
				cur = cell.cdr();
			}
		}

	}

	/**
	 * Compiles the call as a fused integer expression tree, or returns {@code false}
	 * (emitting nothing) when the form does not qualify -- the caller then runs the
	 * ordinary per-operation path.
	 */
	static boolean tryCompile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (!speedTradesEnabled(ctx) || ctx.asyncResume != null) {
			return false;
		}
		Site site = new Site(cons);
		List<Node> leaves = site.leaves;
		Node root = classify(cons, ctx, java.util.Map.of(), site, 0);
		if (!(root instanceof OpNode)) {
			return false;
		}
		int ops = countOps(root);
		if (ops > MAX_OPS || leaves.size() > MAX_EXPR_LEAVES) {
			return false;
		}
		if (ops < 2 && !hasRawRead(leaves) && !hasConstOperand(root)) {
			return false;
		}

		// block $done (result eqref) { block $bail { guard+unbox each leaf once into
		// an i64 scratch local; fast path; box (inline i31 / _int_new); br $done }
		// boxed fallback } -- a bail (guard or overflow) discards the partial i64
		// stack on its way to $bail and recomputes generically.
		int savedI64 = ctx.nextI64Local;
		evalLeaves(leaves, ctx);
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.write(Instruction.BLOCK, 0x40);
		emitLeafUnboxes(leaves, ctx);
		emitFast(root, ctx);
		int rootI64 = ctx.allocI64Temp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writeI64LocalIndex(rootI64);
		emitBoxI64FromSlot(rootI64, ctx);
		ctx.writer.write(Instruction.BR, 1);
		ctx.writer.write(Instruction.END);
		emitFallback(root, ctx);
		ctx.writer.write(Instruction.END);
		ctx.nextI64Local = savedI64;
		return true;
	}

	/**
	 * Whether any leaf reads raw (an unboxed local's snapshot or a packed element). A
	 * SINGLE fused operation is only worth the double emission when it does: the raw read
	 * plus one i64 op beats the generic helper's dispatch there ({@code (- i 2)}
	 * loop-index math over an unboxed counter paid a {@code _rat_sub} call per
	 * evaluation), where a single op over plain boxed leaves would not.
	 */
	private static boolean hasRawRead(List<Node> leaves) {
		for (Node leaf : leaves) {
			if (leaf instanceof RawLeaf || leaf instanceof ArefLeaf) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the root operation has a literal operand. A single op with one, like the
	 * {@code (+ start 1)} an {@code incf} of a plain local or parameter expands to, also
	 * fuses: the i64 constant plus a checked {@code _fx_*} (or plain bitwise) op beats
	 * the generic helper's full tier dispatch. A single op over two plain boxed leaves
	 * keeps the generic call -- nothing about it would run leaner fused.
	 */
	private static boolean hasConstOperand(Node root) {
		return root instanceof OpNode op && op.args().stream().anyMatch(arg -> arg instanceof ConstLeaf);
	}

	/**
	 * Compiles a binary numeric comparison ({@code = < > <= >=}) whose operands are
	 * integer expression trees as ONE raw i64 compare: the operands evaluate/unbox
	 * through the shared leaf machinery, the compare runs on the wasm stack, and the i32
	 * result boxes once into the cached {@code t} / nil -- no {@code _rat_cmp_bits}
	 * dispatch (which itself calls {@code _rat_cmp}) and no boxed operand reads, which is
	 * what every loop-termination test ({@code (< i 64)}) paid per iteration. A bail (a
	 * float or ratio operand, i64 overflow inside a subtree) recomputes the SAME leaves
	 * through the generic {@code _rat_cmp_bits} fallback with the operator's mask, so NaN
	 * and cross-tier comparisons keep the generic result exactly. Skips the
	 * both-plain-leaves shape ({@code (< x y)} with nothing fusable on either side) to
	 * keep the emission of generic code unchanged; returns {@code false} (emitting
	 * nothing) when it does not apply.
	 */
	static boolean tryCompileCompare(LispCons cons, WasmLispCompiler.Ctx ctx, int i64Opcode, int cmpMask) {
		if (!speedTradesEnabled(ctx) || ctx.asyncResume != null) {
			return false;
		}
		List<LispVal> parts = cons.toList();
		Site site = new Site(cons);
		List<Node> leaves = site.leaves;
		Node left = classify(parts.get(1), ctx, java.util.Map.of(), site, 0);
		if (left == null) {
			return false;
		}
		Node right = classify(parts.get(2), ctx, java.util.Map.of(), site, 0);
		if (right == null) {
			return false;
		}
		if (countOps(left) + countOps(right) > MAX_OPS || leaves.size() > MAX_EXPR_LEAVES) {
			return false;
		}
		if (left instanceof ExprLeaf && right instanceof ExprLeaf) {
			return false;
		}
		// block $done (result i32) { block $bail { leaf unboxes; fast left; fast
		// right; i64 compare; br $done } boxed fallback -> _rat_cmp_bits & mask } end;
		// then the shared i32 -> t/nil boxing.
		int savedI64 = ctx.nextI64Local;
		evalLeaves(leaves, ctx);
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.write(Type.I32);
		ctx.writer.write(Instruction.BLOCK, 0x40);
		emitLeafUnboxes(leaves, ctx);
		emitFast(left, ctx);
		emitFast(right, ctx);
		ctx.writer.write(i64Opcode);
		ctx.writer.write(Instruction.BR, 1);
		ctx.writer.write(Instruction.END);
		emitFallback(left, ctx);
		emitFallback(right, ctx);
		emitCall(WasmLispCompiler.FUNC_RAT_CMP_BITS, ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(cmpMask);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.END);
		WasmEmitHelper.emitBoolFromI32(ctx);
		ctx.nextI64Local = savedI64;
		return true;
	}

	/**
	 * The RAW-result variant behind the packed integer-vector store ({@code %aset} with a
	 * fusable value): compiles {@code expr} to ONE raw {@code i64} on the stack -- the
	 * fast path never boxes at all, and a bail recomputes through the boxed fallback and
	 * unboxes the result with the store semantics (an out-of-i64 fallback value
	 * contributes its low 32 bits, exactly what the masked store keeps). A single
	 * operation qualifies here (unlike the boxed entry point: the raw result saves the
	 * box even for one op), and so does a bare raw READ -- a packed {@code (aref b i)} or
	 * an unboxed local as the whole stored value ({@code copy-to-buffer}-style
	 * vector-to-vector copy loops), which previously boxed every element through the
	 * generic read only for the store to unbox it again. Returns {@code false} (emitting
	 * nothing) when the expression is not an integer operation tree.
	 */
	static boolean tryCompileRaw(LispVal expr, WasmLispCompiler.Ctx ctx) {
		if (!speedTradesEnabled(ctx) || ctx.asyncResume != null) {
			return false;
		}
		Site site = new Site(expr);
		List<Node> leaves = site.leaves;
		Node root = classify(expr, ctx, java.util.Map.of(), site, 0);
		if (root == null || root instanceof ExprLeaf) {
			return false;
		}
		if (root instanceof ConstLeaf c) {
			// A literal (or a tree folded to one): the raw value directly, no blocks.
			ctx.writer.write(Instruction.I64_CONST);
			ctx.writer.writeSignedLeb128(c.value());
			return true;
		}
		int ops = countOps(root);
		if (ops > MAX_OPS || leaves.size() > MAX_EXPR_LEAVES) {
			return false;
		}
		// block $done (result i64) { block $bail { leaf unboxes; fast; br $done }
		// fallback -> boxed; unbox with the store semantics } end
		int savedI64 = ctx.nextI64Local;
		evalLeaves(leaves, ctx);
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.write(Type.I64);
		ctx.writer.write(Instruction.BLOCK, 0x40);
		emitLeafUnboxes(leaves, ctx);
		emitFast(root, ctx);
		ctx.writer.write(Instruction.BR, 1);
		ctx.writer.write(Instruction.END);
		emitFallback(root, ctx);
		int boxedSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(boxedSlot);
		WasmArrayCompiler.emitUnboxIntForStore(ctx, boxedSlot);
		ctx.writer.write(Instruction.END);
		ctx.nextI64Local = savedI64;
		return true;
	}

	/**
	 * How many nested defun/local-function body substitutions a fused tree may perform.
	 */
	private static final int MAX_INLINE_DEPTH = 4;

	/**
	 * A let-bound local function eligible for fused-call substitution: the {@code (let
	 * ((__FLETn_f (lambda ...))) ...)} shape {@code flet} lowers to
	 * ({@code .kb/flet-labels.md}), with fixed plain parameters and a single body
	 * expression that is a closed integer-operation tree over them (the
	 * {@code isClosedIntTree} whitelist widened with calls to fusion-inlinable defuns, so
	 * a {@code sigma0}-style wrapper over {@code rol32} qualifies). Registered by
	 * {@link WasmLetCompiler} for the extent of the binding's body, consumed by
	 * {@code classify} at {@code (funcall __FLETn_f ...)} sites -- todo 194 stage 3.
	 */
	record LocalIntLambda(List<String> params, LispVal body) {
	}

	/**
	 * Classifies a {@code let}-init lambda form as an inlinable local function, or
	 * returns {@code null}. The flet lowering wraps the body in {@code (block name
	 * expr)}; the block is transparent here because an exit form ({@code return-from})
	 * could never pass the closed-integer-tree check.
	 */
	@org.jspecify.annotations.Nullable
	static LocalIntLambda eligibleLocalLambda(LispCons lambdaCons, WasmLispCompiler.Ctx ctx) {
		if (!lambdaCons.isProperList()) {
			return null;
		}
		List<LispVal> parts = lambdaCons.toList();
		if (parts.size() < 3 || !(parts.get(0) instanceof LispSymbol head) || !LispNames.LAMBDA.equals(head.name())) {
			return null;
		}
		List<String> params = new ArrayList<>();
		if (parts.get(1) instanceof LispCons paramCons) {
			if (!paramCons.isProperList()) {
				return null;
			}
			for (LispVal p : paramCons.toList()) {
				if (!(p instanceof LispSymbol ps) || ps.name().startsWith("&")) {
					return null;
				}
				params.add(ps.name());
			}
		}
		else if (!(parts.get(1) instanceof am.ik.rontolisp.LispNil)) {
			return null;
		}
		LispVal body = singleBodyExpr(parts.subList(2, parts.size()));
		if (body instanceof LispCons bodyCons && bodyCons.isProperList() && bodyCons.car() instanceof LispSymbol h
				&& LispNames.BLOCK.equals(h.name())) {
			List<LispVal> blockParts = bodyCons.toList();
			body = blockParts.size() == 3 ? blockParts.get(2) : null;
		}
		if (body == null || !isClosedIntTree(body, params, ctx)) {
			return null;
		}
		return new LocalIntLambda(params, body);
	}

	/**
	 * The {@code funcall} entry point: compiles {@code (funcall var args...)} of a
	 * registered local function as a fused tree (the substituted body becomes the root),
	 * or returns {@code false} (emitting nothing) for anything else.
	 */
	static boolean tryCompileLocalCall(LispCons cons, WasmLispCompiler.Ctx ctx) {
		return cons.cdr() instanceof LispCons fnCell && fnCell.car() instanceof LispSymbol fvar
				&& ctx.localIntLambdas.containsKey(fvar.name()) && tryCompile(cons, ctx);
	}

	/**
	 * A quick syntactic filter for {@link WasmLetCompiler}'s unboxed-local eligibility:
	 * does this assignment value LOOK like an integer-operation root (so a raw store has
	 * a fast path worth having)? Precision does not matter for correctness --
	 * {@link #compileRawStore} falls back to a boxed shadow store for anything that does
	 * not actually classify.
	 */
	static boolean isRawAssignShaped(LispVal expr, WasmLispCompiler.Ctx ctx) {
		if (expr instanceof LispInteger) {
			return true;
		}
		if (expr instanceof LispSymbol sym) {
			// An outer unboxed local: the assignment is a raw-to-raw slot copy.
			return ctx.rawLocals.containsKey(sym.name());
		}
		if (!(expr instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
			return false;
		}
		return switch (head.name()) {
			case LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.MOD, LispNames.REM, LispNames.LOGAND,
					LispNames.LOGIOR, LispNames.LOGXOR, LispNames.LOGNOT, LispNames.ASH, LispNames.ONE_PLUS,
					LispNames.ONE_MINUS, LispNames.LDB, LispNames.AREF ->
				true;
			default -> ctx.inlinableDefuns.containsKey(head.name());
		};
	}

	/**
	 * Compiles an assignment into an unboxed local: the fused fast path stores the raw
	 * i64 into {@code target.i64Slot()} and NULLS the shadow; a bail (or a value that
	 * does not classify as an integer tree at all) stores the BOXED result into the
	 * shadow instead -- the raw slot is then stale and the non-null shadow is
	 * authoritative, whatever tier or type the value is. Leaves NOTHING on the stack; the
	 * caller re-reads through {@link #emitRawLocalBoxedRead} when the assignment's value
	 * is needed.
	 */
	static void compileRawStore(LispVal expr, WasmLispCompiler.Ctx ctx, RawLocal target) {
		if (target.counted()) {
			// A counted induction variable has no shadow to bail into, which is exactly
			// why WasmDotimesCompiler refuses the shape when anything in the loop could
			// assign the name. Reaching here means that scan missed a write.
			throw new IllegalStateException("a counted loop variable cannot be assigned");
		}
		if (expr instanceof LispInteger lit) {
			ctx.writer.write(Instruction.I64_CONST);
			ctx.writer.writeSignedLeb128(lit.value());
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writeI64LocalIndex(target.i64Slot());
			emitNullShadow(target, ctx);
			return;
		}
		if (ctx.asyncResume == null) {
			if (expr instanceof LispSymbol sym && ctx.rawLocals.get(sym.name()) instanceof RawLocal src) {
				// A raw-to-raw copy ((setq a b) with both unboxed) transfers BOTH
				// slots: total for every tier -- whatever the source holds, its shadow
				// (sentinel or boxed value) carries it -- so no guard and no bail.
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writeI64LocalIndex(src.i64Slot());
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writeI64LocalIndex(target.i64Slot());
				if (src.counted()) {
					// A counted source has no shadow: its raw slot is always
					// authoritative, so the target's is too.
					emitNullShadow(target, ctx);
					return;
				}
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(src.shadowSlot());
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(target.shadowSlot());
				return;
			}
			Site site = new Site(expr);
			List<Node> leaves = site.leaves;
			Node root = classify(expr, ctx, java.util.Map.of(), site, 0);
			if (root instanceof ConstLeaf c) {
				// A tree folded to a literal: the raw value directly, no blocks.
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(c.value());
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writeI64LocalIndex(target.i64Slot());
				emitNullShadow(target, ctx);
				return;
			}
			if ((root instanceof OpNode || root instanceof ArefLeaf) && countOps(root) <= MAX_OPS
					&& leaves.size() <= MAX_EXPR_LEAVES) {
				int savedI64 = ctx.nextI64Local;
				evalLeaves(leaves, ctx);
				// block $done { block $bail { unboxes; fast; raw store; null shadow;
				// br $done } fallback -> shadow store } end
				ctx.writer.write(Instruction.BLOCK, 0x40);
				ctx.writer.write(Instruction.BLOCK, 0x40);
				emitLeafUnboxes(leaves, ctx);
				emitFast(root, ctx);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writeI64LocalIndex(target.i64Slot());
				emitNullShadow(target, ctx);
				ctx.writer.write(Instruction.BR, 1);
				ctx.writer.write(Instruction.END);
				emitFallback(root, ctx);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(target.shadowSlot());
				ctx.writer.write(Instruction.END);
				ctx.nextI64Local = savedI64;
				return;
			}
		}
		WasmExprCompiler.compileExpr(expr, ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(target.shadowSlot());
	}

	// Marks the raw i64 slot authoritative: shadow := the module's raw-local
	// sentinel (a private TYPE_CELL instance). Null cannot be the marker -- nil IS
	// null, and a local assigned nil must read back as nil.
	private static void emitNullShadow(RawLocal target, WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(ctx.rawSentinelGlobalIndex);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(target.shadowSlot());
	}

	// Pushes i32 1 when the shadow in `slot` is the sentinel (raw value valid).
	private static void emitShadowIsSentinel(int slot, WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(ctx.rawSentinelGlobalIndex);
		ctx.writer.write(Instruction.REF_EQ);
	}

	/**
	 * Reads an unboxed local as an ordinary boxed value: the shadow when it is not the
	 * sentinel, else the raw i64 boxed (an i31 in the fixnum range, so a loop-counter
	 * read allocates nothing).
	 *
	 * <p>
	 * The whole sequence lives in the shared {@code _ub_read} helper
	 * ({@link WasmFxRuntimeBuilder#buildUbReadBody}) rather than inline, so an occurrence
	 * costs three instructions instead of ~42 bytes. It used to inline because
	 * {@code _int_new}'s call overhead dominated for loop counters -- but stage 4's fused
	 * comparisons took every hot counter read onto the RAW path, which never reaches
	 * here, and the sites that remain are cold generic readers in library code: 19,392 of
	 * them in a cl-postgres component, 9.5% of the module. That is exactly the
	 * "re-measure before restructuring" trigger {@code .kb/wasm-unboxed-locals.md}
	 * recorded.
	 */
	static void emitRawLocalBoxedRead(RawLocal raw, WasmLispCompiler.Ctx ctx) {
		if (raw.counted()) {
			emitCountedBox(raw.i64Slot(), ctx);
			return;
		}
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(raw.shadowSlot());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(raw.i64Slot());
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_UB_READ);
	}

	/**
	 * Boxes a COUNTED loop variable's i64 slot: a bare {@code ref.i31}, with neither the
	 * range test {@link #emitBoxI64FromSlot} needs nor the shadow test
	 * {@link #emitRawLocalBoxedRead} needs. A literal bound below the i31 ceiling is what
	 * buys both away ({@link WasmDotimesCompiler}).
	 */
	static void emitCountedBox(int i64Slot, WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(i64Slot);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	/**
	 * Boxes the raw i64 held in an i64 scratch/raw local into an eqref: the i31 range
	 * boxes inline ({@code ref.i31}, allocation- and call-free -- a loop counter's every
	 * read and most fused-site roots take this arm); only an out-of-range value pays the
	 * {@code _int_new} call and its TYPE_BIGNUM allocation.
	 */
	private static void emitBoxI64FromSlot(int i64Slot, WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(i64Slot);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(33);
		ctx.writer.write(Instruction.I64_SHL);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(33);
		ctx.writer.write(Instruction.I64_SHR_S);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(i64Slot);
		ctx.writer.write(Instruction.I64_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(i64Slot);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(i64Slot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * Whether a defun qualifies for fused-call substitution: fixed arity (no lambda-list
	 * markers), and a single body expression (after any leading {@code declare}s) that is
	 * a CLOSED integer-operation tree over the parameters -- every symbol in it is a
	 * parameter or a whitelisted operator head ({@code + - * mod rem logand logior
	 * logxor lognot ash 1+ 1- ldb byte aref}), every literal an integer. Closedness is
	 * what makes the substitution hygienic (the body cannot capture a caller binding) and
	 * the pure-operator whitelist is what makes the fallback's recomputation safe.
	 * Uniqueness of the defun (single definition, non-{@code --dynamic}) is checked by
	 * the caller in {@code WasmLispCompiler}.
	 */
	static boolean isInlinableDefun(WasmLispCompiler.DefunDecl defun) {
		if (defun.variadic()) {
			return false;
		}
		for (String param : defun.paramNames()) {
			if (param.startsWith("&")) {
				return false;
			}
		}
		LispVal body = singleBodyExpr(defun.bodyExprs());
		return body != null && isClosedIntTree(body, defun.paramNames());
	}

	// The single non-declare body expression, or null when the body has none or more
	// than one.
	@org.jspecify.annotations.Nullable
	private static LispVal singleBodyExpr(List<LispVal> bodyExprs) {
		LispVal single = null;
		for (LispVal expr : bodyExprs) {
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& LispNames.DECLARE.equals(head.name())) {
				continue;
			}
			if (single != null) {
				return null;
			}
			single = expr;
		}
		return single;
	}

	private static boolean isClosedIntTree(LispVal expr, List<String> params) {
		return isClosedIntTree(expr, params, null);
	}

	/**
	 * With a non-null {@code ctx}, a call to a fusion-inlinable defun also qualifies as a
	 * tree node (a local function like sigma0 wraps {@code rol32}); the defun's own body
	 * was validated when it was collected. Defun eligibility itself always passes
	 * {@code null} -- it is decided before any {@code Ctx} exists, and keeping defun
	 * bodies self-contained avoids order dependence between their definitions.
	 */
	private static boolean isClosedIntTree(LispVal expr, List<String> params,
			WasmLispCompiler.@org.jspecify.annotations.Nullable Ctx ctx) {
		if (expr instanceof LispInteger) {
			return true;
		}
		if (expr instanceof LispSymbol sym) {
			return params.contains(sym.name());
		}
		if (!(expr instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
			return false;
		}
		List<LispVal> parts = cons.toList();
		int arity = parts.size() - 1;
		boolean headOk = switch (head.name()) {
			case LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.LOGAND, LispNames.LOGIOR, LispNames.LOGXOR ->
				arity >= 2;
			case LispNames.MOD, LispNames.REM, LispNames.ASH, LispNames.AREF, LispNames.BYTE, LispNames.LDB ->
				arity == 2;
			case LispNames.LOGNOT, LispNames.ONE_PLUS, LispNames.ONE_MINUS -> arity == 1;
			default -> false;
		};
		if (!headOk) {
			if (ctx == null) {
				return false;
			}
			WasmLispCompiler.DefunDecl defun = ctx.inlinableDefuns.get(head.name());
			if (defun == null || arity != defun.paramNames().size()) {
				return false;
			}
		}
		for (int i = 1; i < parts.size(); i++) {
			if (!isClosedIntTree(parts.get(i), params, ctx)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Classifies an expression into a fusion tree. Anything that is not a literal
	 * i64-range integer or a fusable operation over fusable arguments becomes an
	 * {@link ExprLeaf}, compiled by the ordinary expression compiler and guarded at
	 * runtime. Leaf nodes are REGISTERED into {@code leaves} at creation, so the leaf
	 * evaluation order is the source order of the ORIGINAL call arguments -- inlined
	 * defun bodies reuse the already-registered argument nodes and add nothing.
	 *
	 * <p>
	 * A call to an inlinable defun ({@code Ctx.inlinableDefuns}: mod32+/rol32-style
	 * one-liner arithmetic wrappers) substitutes the defun's body with the parameters
	 * bound to the classified arguments: a parameter used ONCE takes the argument's tree
	 * directly (fusion continues through it), a parameter used more than once takes the
	 * argument demoted to a shared leaf (evaluated once into its scratch local, read at
	 * every occurrence -- the call-by-value semantics). The fallback recomputes the SAME
	 * substituted tree through the generic helpers, which computes exactly what the real
	 * call chain would: the same operations over the same values.
	 */
	@org.jspecify.annotations.Nullable
	private static Node classify(LispVal expr, WasmLispCompiler.Ctx ctx, java.util.Map<String, Node> env, Site site,
			int depth) {
		List<Node> leaves = site.leaves;
		if (expr instanceof LispInteger i) {
			return new ConstLeaf(i.value());
		}
		if (expr instanceof LispSymbol sym) {
			Node bound = env.get(sym.name());
			if (bound != null) {
				return bound;
			}
			if (!env.isEmpty()) {
				// A free symbol inside an inlined body would compile in the CALLER's
				// scope -- a hygiene violation. Bodies are pre-checked closed, so this
				// is a defensive bail, not a reachable path.
				return null;
			}
			RawLocal raw = ctx.rawLocals.get(sym.name());
			if (raw != null) {
				if (!site.assignedNames.contains(sym.name())) {
					// No leaf in this site can reassign the local, so every occurrence
					// may share ONE snapshot/resolve instead of paying its own.
					RawLeaf shared = site.sharedRawLeaves.get(sym.name());
					if (shared == null) {
						shared = new RawLeaf(raw);
						site.sharedRawLeaves.put(sym.name(), shared);
						registerLeaf(shared, leaves);
					}
					return shared;
				}
				return registerLeaf(new RawLeaf(raw), leaves);
			}
		}
		if (!(expr instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol sym)) {
			return registerLeaf(new ExprLeaf(expr), leaves);
		}
		List<LispVal> parts = cons.toList();
		int arity = parts.size() - 1;
		String op = sym.name();
		if (LispNames.AREF.equals(op) && arity == 2 && env.isEmpty()) {
			// A rank-1 aref: the fast path reads a packed integer vector's element raw
			// (no _int_new box); any other array bails to the generic aref fallback.
			// Inside an inlined body (env non-empty) the array/index may be parameter
			// references, which the slot-based ArefLeaf cannot express;
			// classifyInlineAref
			// handles the parameter-shaped case below.
			return registerLeaf(new ArefLeaf(parts.get(1), parts.get(2)), leaves);
		}
		if (LispNames.LDB.equals(op) && arity == 2) {
			// (ldb (byte s p) x) with a literal byte spec lowers to its pure
			// logand/ash expansion, which classifies as an ordinary subtree -- exactly
			// the code the unfused compiler would emit for it.
			if (parts.get(1) instanceof LispCons spec && spec.car() instanceof LispSymbol specHead
					&& LispNames.BYTE.equals(specHead.name()) && spec.cdr() instanceof LispCons sCell
					&& sCell.car() instanceof LispInteger && sCell.cdr() instanceof LispCons pCell
					&& pCell.car() instanceof LispInteger && pCell.cdr() instanceof am.ik.rontolisp.LispNil) {
				return classify(LispMacroExpander.expandLdb(cons), ctx, env, site, depth);
			}
			return env.isEmpty() ? registerLeaf(new ExprLeaf(expr), leaves) : null;
		}
		if (LispNames.FUNCALL.equals(op) && arity >= 1 && parts.get(1) instanceof LispSymbol fvar
				&& !env.containsKey(fvar.name()) && depth < MAX_INLINE_DEPTH) {
			// (funcall __FLETn_f args...) of a let-bound local function (the flet
			// lowering): substitute its body exactly like an inlinable defun's. The
			// function-position read of the variable is pure, so eliding it is
			// unobservable.
			LocalIntLambda lambda = ctx.localIntLambdas.get(fvar.name());
			if (lambda != null && arity - 1 == lambda.params().size()) {
				Node substituted = substituteCall(lambda.params(), lambda.body(), parts.subList(2, parts.size()), ctx,
						env, site, depth);
				if (substituted != null) {
					return substituted;
				}
			}
		}
		WasmLispCompiler.DefunDecl inlinable = ctx.inlinableDefuns.get(op);
		if (inlinable != null && arity == inlinable.paramNames().size() && depth < MAX_INLINE_DEPTH) {
			LispVal body = java.util.Objects.requireNonNull(singleBodyExpr(inlinable.bodyExprs()));
			Node substituted = substituteCall(inlinable.paramNames(), body, parts.subList(1, parts.size()), ctx, env,
					site, depth);
			if (substituted != null) {
				return substituted;
			}
			// The body did not classify under this env (a parameter-shaped aref, say):
			// fall through to the ordinary leaf treatment of the call itself.
		}
		boolean fusable = switch (op) {
			case LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.LOGAND, LispNames.LOGIOR, LispNames.LOGXOR ->
				arity >= 2;
			case LispNames.MOD, LispNames.REM, LispNames.ASH -> arity == 2;
			case LispNames.LOGNOT, LispNames.ONE_PLUS, LispNames.ONE_MINUS -> arity == 1;
			default -> false;
		};
		if (!fusable) {
			// Inside an inlined body every form must classify (an unfusable form would
			// compile in the CALLER's scope and break hygiene); at the top level it is
			// an ordinary guarded leaf.
			return env.isEmpty() ? registerLeaf(new ExprLeaf(expr), leaves) : null;
		}
		for (int i = 1; i < parts.size(); i++) {
			// A literal double keeps the node on the existing f64 literal path; any
			// other non-integer literal (ratio, big integer) would make the fast path
			// pointless -- the generic compiler owns those shapes.
			if (parts.get(i) instanceof LispDouble || parts.get(i) instanceof am.ik.rontolisp.LispBigInteger
					|| parts.get(i) instanceof am.ik.rontolisp.LispRatio) {
				return env.isEmpty() ? registerLeaf(new ExprLeaf(expr), leaves) : null;
			}
		}
		List<Node> args = new ArrayList<>(arity);
		for (int i = 1; i < parts.size(); i++) {
			Node arg = classify(parts.get(i), ctx, env, site, depth);
			if (arg == null) {
				return null;
			}
			args.add(arg);
		}
		return switch (op) {
			case LispNames.ONE_PLUS -> makeOp(LispNames.ADD, List.of(args.get(0), new ConstLeaf(1)));
			case LispNames.ONE_MINUS -> makeOp(LispNames.SUB, List.of(args.get(0), new ConstLeaf(1)));
			default -> makeOp(op, args);
		};
	}

	/**
	 * Builds an operation node, folding it to a constant when every argument is a literal
	 * and the exact result fits an i64 -- inlined bodies produce shapes like
	 * {@code (ash a (- s 32))} with both shift operands literal, where folding turns a
	 * checked {@code _fx_sub} + {@code _fx_ash} helper pair into a plain shift. Folding
	 * never changes a result: it computes exactly what the fast path would (and bails to
	 * the ordinary node on overflow or a zero divisor, so the runtime's promotion/trap
	 * behavior is preserved).
	 */
	private static Node makeOp(String op, List<Node> args) {
		for (Node arg : args) {
			if (!(arg instanceof ConstLeaf)) {
				return new OpNode(op, args);
			}
		}
		try {
			long acc = ((ConstLeaf) args.get(0)).value();
			if (LispNames.LOGNOT.equals(op)) {
				return new ConstLeaf(~acc);
			}
			for (int i = 1; i < args.size(); i++) {
				long v = ((ConstLeaf) args.get(i)).value();
				acc = switch (op) {
					case LispNames.ADD -> Math.addExact(acc, v);
					case LispNames.SUB -> Math.subtractExact(acc, v);
					case LispNames.MUL -> Math.multiplyExact(acc, v);
					case LispNames.LOGAND -> acc & v;
					case LispNames.LOGIOR -> acc | v;
					case LispNames.LOGXOR -> acc ^ v;
					case LispNames.MOD -> Math.floorMod(acc, v);
					case LispNames.REM -> acc % v;
					case LispNames.ASH -> foldAsh(acc, v);
					default -> throw new ArithmeticException("not foldable");
				};
			}
			return new ConstLeaf(acc);
		}
		catch (ArithmeticException overflowOrZeroDivide) {
			return new OpNode(op, args);
		}
	}

	private static long foldAsh(long value, long count) {
		if (count <= 0) {
			return value >> Math.min(-count, 63);
		}
		if (count > 62) {
			throw new ArithmeticException("shift out of i64");
		}
		long shifted = value << count;
		if ((shifted >> count) != value) {
			throw new ArithmeticException("shift out of i64");
		}
		return shifted;
	}

	/**
	 * Substitutes an inlinable body with the parameters bound to the classified
	 * arguments. Each argument is classified ONCE (registering its leaves in source
	 * order, so evaluation order and once-only side effects are preserved) and the
	 * resulting node is shared across every occurrence of its parameter -- a shared
	 * interior node re-emits its pure arithmetic, a shared leaf re-reads its scratch
	 * slot. On failure the leaves registered by this attempt are rolled back, so the
	 * caller's fall-through leaf treatment does not ALSO evaluate them (evaluating a
	 * side-effecting argument twice).
	 */
	@org.jspecify.annotations.Nullable
	private static Node substituteCall(List<String> params, LispVal body, List<LispVal> args, WasmLispCompiler.Ctx ctx,
			java.util.Map<String, Node> env, Site site, int depth) {
		List<Node> leaves = site.leaves;
		int mark = leaves.size();
		java.util.Map<String, Node> callEnv = new java.util.HashMap<>();
		Node substituted = null;
		for (int i = 0; i < params.size(); i++) {
			Node argNode = classify(args.get(i), ctx, env, site, depth);
			if (argNode == null) {
				break;
			}
			callEnv.put(params.get(i), argNode);
		}
		if (callEnv.size() == params.size()) {
			substituted = classify(body, ctx, callEnv, site, depth + 1);
		}
		if (substituted == null) {
			leaves.subList(mark, leaves.size()).clear();
		}
		return substituted;
	}

	private static Node registerLeaf(Node leaf, List<Node> leaves) {
		leaves.add(leaf);
		return leaf;
	}

	/** Counts fused operations (an n-ary node left-folds into arity - 1 binary ops). */
	private static int countOps(Node node) {
		return switch (node) {
			case ExprLeaf ignored -> 0;
			case ArefLeaf ignored -> 0;
			case RawLeaf ignored -> 0;
			case ConstLeaf ignored -> 0;
			case OpNode op -> {
				int ops = Math.max(1, op.args().size() - 1);
				for (Node arg : op.args()) {
					ops += countOps(arg);
				}
				yield ops;
			}
		};
	}

	/**
	 * Evaluates every non-constant leaf ONCE, left to right (the same observable order as
	 * the generic path's argument evaluation), into scratch locals both paths read. An
	 * aref leaf evaluates its array then its index, exactly like the generic aref
	 * argument order; a raw-local leaf snapshots its (i64, shadow) pair so a later leaf's
	 * side effect cannot change what this read observes.
	 */
	private static void evalLeaves(List<Node> leaves, WasmLispCompiler.Ctx ctx) {
		for (Node node : leaves) {
			if (node instanceof ExprLeaf leaf) {
				WasmExprCompiler.compileExpr(leaf.expr, ctx);
				leaf.slot = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(leaf.slot);
			}
			else if (node instanceof ArefLeaf aref) {
				WasmExprCompiler.compileExpr(aref.arrayExpr, ctx);
				aref.arrSlot = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(aref.arrSlot);
				WasmExprCompiler.compileExpr(aref.indexExpr, ctx);
				aref.idxSlot = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(aref.idxSlot);
			}
			else if (node instanceof RawLeaf raw) {
				if (raw.src.counted()) {
					// A counted loop variable cannot be assigned anywhere in the loop,
					// so there is nothing a later leaf could change: the leaf IS the
					// slot, snapshot-free and guard-free.
					raw.snapI64 = raw.src.i64Slot();
					continue;
				}
				raw.snapI64 = ctx.allocI64Temp();
				raw.snapShadow = ctx.allocTemp();
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writeI64LocalIndex(raw.src.i64Slot());
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writeI64LocalIndex(raw.snapI64);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(raw.src.shadowSlot());
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(raw.snapShadow);
			}
		}
	}

	/**
	 * Guards and unboxes every non-constant leaf ONCE, in registration (source) order,
	 * into an i64 scratch local -- the fast path re-reads the local at every occurrence.
	 * Before todo 194 stage 3 the guard was re-emitted at every occurrence of a shared
	 * leaf, which made inlined local-function bodies (whose parameters are used
	 * repeatedly) pay more in guards than they saved in dispatch. Emitted directly inside
	 * the bail block: a failed guard branches to the fallback ({@code br_if} at depth 0,
	 * or depth 1 from inside the ExprLeaf guard's own {@code if}).
	 */
	private static void emitLeafUnboxes(List<Node> leaves, WasmLispCompiler.Ctx ctx) {
		for (Node node : leaves) {
			// The _fx_val semantics inlined (an i31's value, a TYPE_BIGNUM's field,
			// anything else bails): the profile showed the call wrapper alone costing
			// more than the two type tests it performs.
			if (node instanceof ExprLeaf leaf) {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(leaf.slot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
				ctx.writer.writeHeapType(Type.I31.code());
				ctx.writer.write(Instruction.IF);
				ctx.writer.write(Type.I64);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(leaf.slot);
				WasmEmitHelper.castI31GetS(ctx);
				ctx.writer.write(Instruction.I64_EXTEND_S_I32);
				ctx.writer.write(Instruction.ELSE);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(leaf.slot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
				ctx.writer.write(Instruction.I32_EQZ);
				ctx.writer.write(Instruction.BR_IF, 1);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(leaf.slot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_BIGNUM);
				ctx.writer.writeUnsignedLeb128(0);
				ctx.writer.write(Instruction.END);
				leaf.i64Slot = ctx.allocI64Temp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writeI64LocalIndex(leaf.i64Slot);
			}
			// The raw element read: bail unless the array is a packed integer vector
			// AND the index an i31; then read data[idx] unsigned as an i64.
			else if (node instanceof ArefLeaf leaf) {
				WasmArrayCompiler.testIntVector(ctx, leaf.arrSlot);
				ctx.writer.write(Instruction.I32_EQZ);
				ctx.writer.write(Instruction.BR_IF, 0);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(leaf.idxSlot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
				ctx.writer.writeHeapType(Type.I31.code());
				ctx.writer.write(Instruction.I32_EQZ);
				ctx.writer.write(Instruction.BR_IF, 0);
				WasmArrayCompiler.emitPackedIntRead(ctx, leaf.arrSlot, leaf.idxSlot);
				leaf.i64Slot = ctx.allocI64Temp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writeI64LocalIndex(leaf.i64Slot);
			}
			// A raw-local snapshot resolves to snapI64: the raw value when the shadow
			// is null (the common case, no guard at all), else the shadow's guarded
			// unbox (i31 / TYPE_BIGNUM; anything else -- a float or limb-tier shadow
			// -- bails at depth 2: past the inner guard if and the outer if).
			else if (node instanceof RawLeaf raw) {
				if (raw.src.counted()) {
					// Nothing to resolve: emitFast reads the counted slot directly.
					continue;
				}
				emitShadowIsSentinel(raw.snapShadow, ctx);
				ctx.writer.write(Instruction.IF);
				ctx.writer.write(Type.I64);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writeI64LocalIndex(raw.snapI64);
				ctx.writer.write(Instruction.ELSE);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(raw.snapShadow);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
				ctx.writer.writeHeapType(Type.I31.code());
				ctx.writer.write(Instruction.IF);
				ctx.writer.write(Type.I64);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(raw.snapShadow);
				WasmEmitHelper.castI31GetS(ctx);
				ctx.writer.write(Instruction.I64_EXTEND_S_I32);
				ctx.writer.write(Instruction.ELSE);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(raw.snapShadow);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
				ctx.writer.write(Instruction.I32_EQZ);
				ctx.writer.write(Instruction.BR_IF, 2);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(raw.snapShadow);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_BIGNUM);
				ctx.writer.writeUnsignedLeb128(0);
				ctx.writer.write(Instruction.END);
				ctx.writer.write(Instruction.END);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writeI64LocalIndex(raw.snapI64);
			}
		}
	}

	private static void writeI64LocalRead(int i64Slot, WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(i64Slot);
	}

	/**
	 * The raw-i64 fast path over the pre-unboxed leaves. Straight-line code directly
	 * inside the bail block -- checked ops branch to it at depth 0; a taken branch
	 * discards whatever partial i64 stack the tree has built so far.
	 */
	private static void emitFast(Node node, WasmLispCompiler.Ctx ctx) {
		switch (node) {
			case ConstLeaf c -> {
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(c.value());
			}
			case ExprLeaf leaf -> writeI64LocalRead(leaf.i64Slot, ctx);
			case ArefLeaf leaf -> writeI64LocalRead(leaf.i64Slot, ctx);
			case RawLeaf leaf -> writeI64LocalRead(leaf.snapI64, ctx);
			case OpNode op -> {
				// (mod x 2^k) with a positive power-of-two literal is a plain mask --
				// two's complement makes x & (2^k - 1) the CL mod (divisor-signed
				// result) for ANY i64 x, with no overflow and no helper call. The
				// masked subtree may compute WRAPPED (its low k bits are exact).
				if (LispNames.MOD.equals(op.op()) && op.args().get(1) instanceof ConstLeaf c && c.value() > 0
						&& Long.bitCount(c.value()) == 1) {
					emitFastWrapped(op.args().get(0), ctx);
					ctx.writer.write(Instruction.I64_CONST);
					ctx.writer.writeSignedLeb128(c.value() - 1);
					ctx.writer.write(Instruction.I64_AND);
					return;
				}
				// (ash x -k) with a literal non-positive count is a plain arithmetic
				// right shift (clamped at 63: the value shifts down to its sign) --
				// it cannot overflow, so no helper call.
				if (LispNames.ASH.equals(op.op()) && op.args().get(1) instanceof ConstLeaf c && c.value() <= 0) {
					emitFast(op.args().get(0), ctx);
					ctx.writer.write(Instruction.I64_CONST);
					ctx.writer.writeSignedLeb128(c.value() <= -63 ? 63 : -c.value());
					ctx.writer.write(Instruction.I64_SHR_S);
					return;
				}
				// (logand X mask) with a non-negative literal: the masked result keeps
				// only low bits, which wrap-around i64 arithmetic computes EXACTLY --
				// the whole add/sub/mul/shl-const subtree under the mask can run
				// unchecked (mod32+/rol32-style code pays no _fx_* calls at all).
				if (LispNames.LOGAND.equals(op.op()) && op.args().size() == 2) {
					ConstLeaf mask = op.args().get(1) instanceof ConstLeaf m && m.value() >= 0 ? m
							: op.args().get(0) instanceof ConstLeaf m0 && m0.value() >= 0 ? m0 : null;
					if (mask != null) {
						emitFastWrapped(op.args().get(op.args().get(1) == mask ? 0 : 1), ctx);
						ctx.writer.write(Instruction.I64_CONST);
						ctx.writer.writeSignedLeb128(mask.value());
						ctx.writer.write(Instruction.I64_AND);
						return;
					}
				}
				emitFast(op.args().get(0), ctx);
				for (int i = 1; i < op.args().size(); i++) {
					emitFast(op.args().get(i), ctx);
					emitFastOp(op.op(), ctx);
				}
				if (LispNames.LOGNOT.equals(op.op())) {
					ctx.writer.write(Instruction.I64_CONST);
					ctx.writer.writeSignedLeb128(-1);
					ctx.writer.write(Instruction.I64_XOR);
				}
			}
		}
	}

	/**
	 * Emits a subtree whose consumer only keeps LOW bits (it sits under a literal
	 * {@code logand} mask or a power-of-two {@code mod}): {@code + - *} and
	 * left-{@code ash} by a literal emit as plain wrap-around i64 ops with NO overflow
	 * check -- the low {@code k <= 63} bits of a wrapped result equal the
	 * infinite-precision ones -- and the bitwise ops pass the wrapping license through.
	 * Anything whose value depends on HIGH bits (a right shift, a general mod/rem, a
	 * leaf) emits through the ordinary checked path, whose value is exact.
	 */
	private static void emitFastWrapped(Node node, WasmLispCompiler.Ctx ctx) {
		if (!(node instanceof OpNode op)) {
			emitFast(node, ctx);
			return;
		}
		switch (op.op()) {
			case LispNames.ADD, LispNames.SUB, LispNames.MUL -> {
				emitFastWrapped(op.args().get(0), ctx);
				for (int i = 1; i < op.args().size(); i++) {
					emitFastWrapped(op.args().get(i), ctx);
					ctx.writer.write(switch (op.op()) {
						case LispNames.ADD -> Instruction.I64_ADD;
						case LispNames.SUB -> Instruction.I64_SUB;
						default -> Instruction.I64_MUL;
					});
				}
			}
			case LispNames.LOGAND, LispNames.LOGIOR, LispNames.LOGXOR -> {
				emitFastWrapped(op.args().get(0), ctx);
				for (int i = 1; i < op.args().size(); i++) {
					emitFastWrapped(op.args().get(i), ctx);
					ctx.writer.write(switch (op.op()) {
						case LispNames.LOGAND -> Instruction.I64_AND;
						case LispNames.LOGIOR -> Instruction.I64_OR;
						default -> Instruction.I64_XOR;
					});
				}
			}
			case LispNames.LOGNOT -> {
				emitFastWrapped(op.args().get(0), ctx);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(-1);
				ctx.writer.write(Instruction.I64_XOR);
			}
			case LispNames.ASH -> {
				if (op.args().get(1) instanceof ConstLeaf c && c.value() > 0 && c.value() < 64) {
					emitFastWrapped(op.args().get(0), ctx);
					ctx.writer.write(Instruction.I64_CONST);
					ctx.writer.writeSignedLeb128(c.value());
					ctx.writer.write(Instruction.I64_SHL);
				}
				else {
					emitFast(node, ctx);
				}
			}
			default -> emitFast(node, ctx);
		}
	}

	private static void emitFastOp(String op, WasmLispCompiler.Ctx ctx) {
		switch (op) {
			case LispNames.LOGAND -> ctx.writer.write(Instruction.I64_AND);
			case LispNames.LOGIOR -> ctx.writer.write(Instruction.I64_OR);
			case LispNames.LOGXOR -> ctx.writer.write(Instruction.I64_XOR);
			case LispNames.ADD -> emitCheckedCall(WasmLispCompiler.FUNC_FX_ADD, ctx);
			case LispNames.SUB -> emitCheckedCall(WasmLispCompiler.FUNC_FX_SUB, ctx);
			case LispNames.MUL -> emitCheckedCall(WasmLispCompiler.FUNC_FX_MUL, ctx);
			case LispNames.ASH -> emitCheckedCall(WasmLispCompiler.FUNC_FX_ASH, ctx);
			case LispNames.MOD -> emitCall(WasmLispCompiler.FUNC_FX_MOD, ctx);
			case LispNames.REM -> emitCall(WasmLispCompiler.FUNC_FX_REM, ctx);
			default -> throw new IllegalStateException("Not a fusable operator: " + op);
		}
	}

	private static void emitCheckedCall(int func, WasmLispCompiler.Ctx ctx) {
		emitCall(func, ctx);
		ctx.writer.write(Instruction.BR_IF, 0);
	}

	private static void emitCall(int func, WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(func);
	}

	/**
	 * The boxed fallback: the same tree, left-folded through the generic runtime helpers
	 * the per-operation compilers call ({@code _rat_add}-family, {@code _big_*} bitwise),
	 * reading the leaf locals -- so a bailing fast path costs a recomputation but
	 * reproduces the generic result bit for bit.
	 */
	private static void emitFallback(Node node, WasmLispCompiler.Ctx ctx) {
		switch (node) {
			case ConstLeaf c -> WasmEmitHelper.compileIntegerLiteral(c.value(), ctx);
			case ExprLeaf leaf -> {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(leaf.slot);
			}
			// The ordinary rank-1 aref dispatch from the SAME locals: string, packed
			// float, packed integer and general arrays all behave exactly as an
			// unfused (aref a i) call would (the read is pure, so recomputing it in
			// the fallback is safe).
			case ArefLeaf leaf -> WasmArrayCompiler.emitAref1FromSlots(ctx, leaf.arrSlot, leaf.idxSlot);
			// The snapshot re-boxed: the shadow when non-null, else the raw value
			// through _int_new (an i31 for the fixnum range -- allocation-free).
			case RawLeaf leaf -> {
				if (leaf.src.counted()) {
					// The counted slot is always authoritative and always a fixnum.
					emitCountedBox(leaf.snapI64, ctx);
				}
				else {
					emitShadowIsSentinel(leaf.snapShadow, ctx);
					ctx.writer.write(Instruction.IF);
					ctx.writer.writeRefType(true, Type.EQ.code());
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writeI64LocalIndex(leaf.snapI64);
					ctx.writer.write(Instruction.CALL);
					ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
					ctx.writer.write(Instruction.ELSE);
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writer.writeUnsignedLeb128(leaf.snapShadow);
					ctx.writer.write(Instruction.END);
				}
			}
			case OpNode op -> {
				emitFallback(op.args().get(0), ctx);
				for (int i = 1; i < op.args().size(); i++) {
					emitFallback(op.args().get(i), ctx);
					emitCall(fallbackFunc(op.op()), ctx);
				}
				if (LispNames.LOGNOT.equals(op.op())) {
					emitCall(WasmLispCompiler.FUNC_BIG_NOT, ctx);
				}
			}
		}
	}

	private static int fallbackFunc(String op) {
		return switch (op) {
			case LispNames.ADD -> WasmLispCompiler.FUNC_RAT_ADD;
			case LispNames.SUB -> WasmLispCompiler.FUNC_RAT_SUB;
			case LispNames.MUL -> WasmLispCompiler.FUNC_RAT_MUL;
			case LispNames.MOD -> WasmLispCompiler.FUNC_RAT_MOD;
			case LispNames.REM -> WasmLispCompiler.FUNC_RAT_REM;
			case LispNames.LOGAND -> WasmLispCompiler.FUNC_BIG_AND;
			case LispNames.LOGIOR -> WasmLispCompiler.FUNC_BIG_OR;
			case LispNames.LOGXOR -> WasmLispCompiler.FUNC_BIG_XOR;
			case LispNames.ASH -> WasmLispCompiler.FUNC_BIG_ASH;
			default -> throw new IllegalStateException("Not a fusable operator: " + op);
		};
	}

}
