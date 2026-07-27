package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispMacroExpander;
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

	private sealed interface Node permits OpNode, ConstLeaf, ExprLeaf, ArefLeaf {

	}

	private record OpNode(String op, List<Node> args) implements Node {
	}

	private record ConstLeaf(long value) implements Node {
	}

	private static final class ExprLeaf implements Node {

		final LispVal expr;

		int slot = -1;

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

		ArefLeaf(LispVal arrayExpr, LispVal indexExpr) {
			this.arrayExpr = arrayExpr;
			this.indexExpr = indexExpr;
		}

	}

	/**
	 * Compiles the call as a fused integer expression tree, or returns {@code false}
	 * (emitting nothing) when the form does not qualify -- the caller then runs the
	 * ordinary per-operation path.
	 */
	static boolean tryCompile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (ctx.asyncResume != null) {
			return false;
		}
		List<Node> leaves = new ArrayList<>();
		Node root = classify(cons, ctx, java.util.Map.of(), leaves, 0);
		if (!(root instanceof OpNode)) {
			return false;
		}
		int ops = countOps(root);
		if (ops < 2 || ops > MAX_OPS || leaves.size() > MAX_EXPR_LEAVES) {
			return false;
		}

		// Evaluate every non-constant leaf ONCE, left to right (the same observable
		// order as the generic path's argument evaluation), into scratch locals both
		// paths read. An aref leaf evaluates its array then its index, exactly like the
		// generic aref argument order.
		for (Node node : leaves) {
			if (node instanceof ExprLeaf leaf) {
				WasmExprCompiler.compileExpr(leaf.expr, ctx);
				leaf.slot = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(leaf.slot);
			}
			else if (node instanceof ArefLeaf aref) {
				WasmExprCompiler.compileExpr(aref.arrayExpr, ctx);
				aref.arrSlot = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(aref.arrSlot);
				WasmExprCompiler.compileExpr(aref.indexExpr, ctx);
				aref.idxSlot = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(aref.idxSlot);
			}
		}

		// block $done (result eqref) { block $bail { fast path; _int_new; br $done }
		// boxed fallback } -- a bail (guard or overflow) discards the partial i64
		// stack on its way to $bail and recomputes generically.
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.BLOCK, 0x40);
		emitFast(root, ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
		ctx.writer.write(Instruction.BR, 1);
		ctx.writer.write(Instruction.END);
		emitFallback(root, ctx);
		ctx.writer.write(Instruction.END);
		return true;
	}

	/**
	 * The RAW-result variant behind the packed integer-vector store ({@code %aset} with a
	 * fusable value): compiles {@code expr} to ONE raw {@code i64} on the stack -- the
	 * fast path never boxes at all, and a bail recomputes through the boxed fallback and
	 * unboxes the result with the store semantics (an out-of-i64 fallback value
	 * contributes its low 32 bits, exactly what the masked store keeps). A single
	 * operation qualifies here (unlike the boxed entry point: the raw result saves the
	 * box even for one op). Returns {@code false} (emitting nothing) when the expression
	 * is not an integer operation tree.
	 */
	static boolean tryCompileRaw(LispVal expr, WasmLispCompiler.Ctx ctx) {
		if (ctx.asyncResume != null) {
			return false;
		}
		List<Node> leaves = new ArrayList<>();
		Node root = classify(expr, ctx, java.util.Map.of(), leaves, 0);
		if (!(root instanceof OpNode)) {
			return false;
		}
		int ops = countOps(root);
		if (ops > MAX_OPS || leaves.size() > MAX_EXPR_LEAVES) {
			return false;
		}
		for (Node node : leaves) {
			if (node instanceof ExprLeaf leaf) {
				WasmExprCompiler.compileExpr(leaf.expr, ctx);
				leaf.slot = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(leaf.slot);
			}
			else if (node instanceof ArefLeaf aref) {
				WasmExprCompiler.compileExpr(aref.arrayExpr, ctx);
				aref.arrSlot = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(aref.arrSlot);
				WasmExprCompiler.compileExpr(aref.indexExpr, ctx);
				aref.idxSlot = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(aref.idxSlot);
			}
		}
		// block $done (result i64) { block $bail { fast; br $done } fallback -> boxed;
		// unbox with the store semantics } end
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.write(Type.I64);
		ctx.writer.write(Instruction.BLOCK, 0x40);
		emitFast(root, ctx);
		ctx.writer.write(Instruction.BR, 1);
		ctx.writer.write(Instruction.END);
		emitFallback(root, ctx);
		int boxedSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(boxedSlot);
		WasmArrayCompiler.emitUnboxIntForStore(ctx, boxedSlot);
		ctx.writer.write(Instruction.END);
		return true;
	}

	/** How many nested defun-body substitutions a fused tree may perform. */
	private static final int MAX_INLINE_DEPTH = 4;

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
			return false;
		}
		for (int i = 1; i < parts.size(); i++) {
			if (!isClosedIntTree(parts.get(i), params)) {
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
	private static Node classify(LispVal expr, WasmLispCompiler.Ctx ctx, java.util.Map<String, Node> env,
			List<Node> leaves, int depth) {
		if (expr instanceof LispInteger i) {
			return new ConstLeaf(i.value());
		}
		if (expr instanceof LispSymbol sym) {
			Node bound = env.get(sym.name());
			if (bound != null) {
				return bound;
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
				return classify(LispMacroExpander.expandLdb(cons), ctx, env, leaves, depth);
			}
			return env.isEmpty() ? registerLeaf(new ExprLeaf(expr), leaves) : null;
		}
		WasmLispCompiler.DefunDecl inlinable = ctx.inlinableDefuns.get(op);
		if (inlinable != null && arity == inlinable.paramNames().size() && depth < MAX_INLINE_DEPTH) {
			LispVal body = singleBodyExpr(inlinable.bodyExprs());
			java.util.Map<String, Node> callEnv = new java.util.HashMap<>();
			for (int i = 0; i < arity; i++) {
				String param = inlinable.paramNames().get(i);
				LispVal arg = parts.get(i + 1);
				Node argNode;
				if (countOccurrences(body, param) > 1) {
					// Used more than once: a shared leaf, evaluated exactly once.
					argNode = arg instanceof LispInteger n ? new ConstLeaf(n.value())
							: registerLeaf(new ExprLeaf(arg), leaves);
				}
				else {
					argNode = classify(arg, ctx, env, leaves, depth);
					if (argNode == null) {
						return null;
					}
				}
				callEnv.put(param, argNode);
			}
			Node substituted = classify(java.util.Objects.requireNonNull(body), ctx, callEnv, leaves, depth + 1);
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
			Node arg = classify(parts.get(i), ctx, env, leaves, depth);
			if (arg == null) {
				return null;
			}
			args.add(arg);
		}
		return switch (op) {
			case LispNames.ONE_PLUS -> new OpNode(LispNames.ADD, List.of(args.get(0), new ConstLeaf(1)));
			case LispNames.ONE_MINUS -> new OpNode(LispNames.SUB, List.of(args.get(0), new ConstLeaf(1)));
			default -> new OpNode(op, args);
		};
	}

	private static Node registerLeaf(Node leaf, List<Node> leaves) {
		leaves.add(leaf);
		return leaf;
	}

	private static int countOccurrences(@org.jspecify.annotations.Nullable LispVal expr, String name) {
		if (expr instanceof LispSymbol sym) {
			return name.equals(sym.name()) ? 1 : 0;
		}
		if (expr instanceof LispCons cons) {
			int count = 0;
			LispVal cur = cons;
			while (cur instanceof LispCons cell) {
				count += countOccurrences(cell.car(), name);
				cur = cell.cdr();
			}
			return count + countOccurrences(cur, name);
		}
		return 0;
	}

	/** Counts fused operations (an n-ary node left-folds into arity - 1 binary ops). */
	private static int countOps(Node node) {
		return switch (node) {
			case ExprLeaf ignored -> 0;
			case ArefLeaf ignored -> 0;
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
	 * The raw-i64 fast path. Straight-line code directly inside the bail block except for
	 * the per-leaf guard's own {@code if} -- ops branch to the bail block at depth 0, the
	 * guard's not-an-i64-integer branch at depth 1; a taken branch discards whatever
	 * partial i64 stack the tree has built so far.
	 */
	private static void emitFast(Node node, WasmLispCompiler.Ctx ctx) {
		switch (node) {
			case ConstLeaf c -> {
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(c.value());
			}
			// The _fx_val semantics inlined (an i31's value, a TYPE_BIGNUM's field,
			// anything else bails): the leaf guard runs once per leaf per evaluation,
			// and the profile showed the call wrapper alone costing more than the two
			// type tests it performs.
			case ExprLeaf leaf -> {
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(leaf.slot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
				ctx.writer.writeHeapType(Type.I31.code());
				ctx.writer.write(Instruction.IF);
				ctx.writer.write(Type.I64);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(leaf.slot);
				WasmEmitHelper.castI31GetS(ctx);
				ctx.writer.write(Instruction.I64_EXTEND_S_I32);
				ctx.writer.write(Instruction.ELSE);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(leaf.slot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
				ctx.writer.write(Instruction.I32_EQZ);
				ctx.writer.write(Instruction.BR_IF, 1);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(leaf.slot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_BIGNUM);
				ctx.writer.writeSignedLeb128(0);
				ctx.writer.write(Instruction.END);
			}
			// The raw element read: bail unless the array is a packed integer vector
			// AND the index an i31 (the guards run at depth 0 relative to the bail
			// block -- leaf-guard ifs are self-contained, so every operand position
			// sits directly inside it); then read data[idx] unsigned as an i64.
			case ArefLeaf leaf -> {
				WasmArrayCompiler.testIntVector(ctx, leaf.arrSlot);
				ctx.writer.write(Instruction.I32_EQZ);
				ctx.writer.write(Instruction.BR_IF, 0);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(leaf.idxSlot);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
				ctx.writer.writeHeapType(Type.I31.code());
				ctx.writer.write(Instruction.I32_EQZ);
				ctx.writer.write(Instruction.BR_IF, 0);
				WasmArrayCompiler.emitPackedIntRead(ctx, leaf.arrSlot, leaf.idxSlot);
			}
			case OpNode op -> {
				// (mod x 2^k) with a positive power-of-two literal is a plain mask --
				// two's complement makes x & (2^k - 1) the CL mod (divisor-signed
				// result) for ANY i64 x, with no overflow and no helper call.
				if (LispNames.MOD.equals(op.op()) && op.args().get(1) instanceof ConstLeaf c && c.value() > 0
						&& Long.bitCount(c.value()) == 1) {
					emitFast(op.args().get(0), ctx);
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
		ctx.writer.writeSignedLeb128(func);
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
				ctx.writer.writeSignedLeb128(leaf.slot);
			}
			// The ordinary rank-1 aref dispatch from the SAME locals: string, packed
			// float, packed integer and general arrays all behave exactly as an
			// unfused (aref a i) call would (the read is pure, so recomputing it in
			// the fallback is safe).
			case ArefLeaf leaf -> WasmArrayCompiler.emitAref1FromSlots(ctx, leaf.arrSlot, leaf.idxSlot);
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
