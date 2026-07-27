package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
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

	private sealed interface Node permits OpNode, ConstLeaf, ExprLeaf {

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
	 * Compiles the call as a fused integer expression tree, or returns {@code false}
	 * (emitting nothing) when the form does not qualify -- the caller then runs the
	 * ordinary per-operation path.
	 */
	static boolean tryCompile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (ctx.asyncResume != null) {
			return false;
		}
		Node root = classify(cons);
		if (!(root instanceof OpNode)) {
			return false;
		}
		List<ExprLeaf> leaves = new ArrayList<>();
		int ops = collect(root, leaves);
		if (ops < 2 || ops > MAX_OPS || leaves.size() > MAX_EXPR_LEAVES) {
			return false;
		}

		// Evaluate every non-constant leaf ONCE, left to right (the same observable
		// order as the generic path's argument evaluation), into scratch locals both
		// paths read.
		for (ExprLeaf leaf : leaves) {
			WasmExprCompiler.compileExpr(leaf.expr, ctx);
			leaf.slot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(leaf.slot);
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
	 * Classifies an expression into a fusion tree. Anything that is not a literal
	 * i64-range integer or a fusable operation over fusable arguments becomes an
	 * {@link ExprLeaf}, compiled by the ordinary expression compiler and guarded at
	 * runtime.
	 */
	private static Node classify(LispVal expr) {
		if (expr instanceof LispInteger i) {
			return new ConstLeaf(i.value());
		}
		if (!(expr instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol sym)) {
			return new ExprLeaf(expr);
		}
		List<LispVal> parts = cons.toList();
		int arity = parts.size() - 1;
		String op = sym.name();
		boolean fusable = switch (op) {
			case LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.LOGAND, LispNames.LOGIOR, LispNames.LOGXOR ->
				arity >= 2;
			case LispNames.MOD, LispNames.REM, LispNames.ASH -> arity == 2;
			case LispNames.LOGNOT, LispNames.ONE_PLUS, LispNames.ONE_MINUS -> arity == 1;
			default -> false;
		};
		if (!fusable) {
			return new ExprLeaf(expr);
		}
		for (int i = 1; i < parts.size(); i++) {
			// A literal double keeps the node on the existing f64 literal path; any
			// other non-integer literal (ratio, big integer) would make the fast path
			// pointless -- the generic compiler owns those shapes.
			if (parts.get(i) instanceof LispDouble || parts.get(i) instanceof am.ik.rontolisp.LispBigInteger
					|| parts.get(i) instanceof am.ik.rontolisp.LispRatio) {
				return new ExprLeaf(expr);
			}
		}
		List<Node> args = new ArrayList<>(arity);
		for (int i = 1; i < parts.size(); i++) {
			args.add(classify(parts.get(i)));
		}
		return switch (op) {
			case LispNames.ONE_PLUS -> new OpNode(LispNames.ADD, List.of(args.get(0), new ConstLeaf(1)));
			case LispNames.ONE_MINUS -> new OpNode(LispNames.SUB, List.of(args.get(0), new ConstLeaf(1)));
			default -> new OpNode(op, args);
		};
	}

	/** Counts fused operations and collects the expression leaves in evaluation order. */
	private static int collect(Node node, List<ExprLeaf> leaves) {
		return switch (node) {
			case ExprLeaf leaf -> {
				leaves.add(leaf);
				yield 0;
			}
			case ConstLeaf ignored -> 0;
			// An n-ary node left-folds into (arity - 1) binary operations, matching the
			// generic path.
			case OpNode op -> {
				int ops = Math.max(1, op.args().size() - 1);
				for (Node arg : op.args()) {
					ops += collect(arg, leaves);
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
