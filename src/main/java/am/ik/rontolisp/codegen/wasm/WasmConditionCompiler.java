package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles a form in TEST position -- the test of an {@code if} or {@code while}, an
 * operand of an {@code and}/{@code or} that is itself a test -- as a raw i32 truth value
 * (0 = false, non-0 = true) instead of a Lisp boolean. A predicate's natural result is
 * the i32 of a {@code ref.test}, {@code ref.is_null} or {@code ref.eq}; boxing it into
 * {@code t}/nil ({@code if (result eqref) call $t else ref.null end}, a {@code _t_sym}
 * call per true answer) only for the consumer to {@code ref.is_null} the box again cost
 * nine bytes and a call per test, and {@code (not (consp x))} paid it twice. The
 * hello-clack Worker carried 3,304 of those round trips.
 *
 * <p>
 * {@code negated} asks for the COMPLEMENT: non-0 exactly when the test is false, which is
 * what an {@code if} (whose wasm THEN arm is the Lisp else arm) and a loop's exit
 * {@code br_if} want. A {@code not}/{@code null} flips the request instead of emitting an
 * {@code i32.eqz}; {@code and}/{@code or} short-circuit through {@code if (result i32)}
 * blocks over their operands compiled the same way, so a chain of predicates never
 * materialises a box; anything else is compiled as a value and tested with one
 * {@code ref.is_null}. The value-position predicates ({@code WasmNullPredCompiler},
 * {@code WasmConspCompiler}, ...) stay what they are: a value needs the box.
 */
final class WasmConditionCompiler {

	private WasmConditionCompiler() {
	}

	/**
	 * Emits an i32 that is non-0 exactly when {@code test} is true (false when
	 * {@code negated}).
	 * @param test the test form
	 * @param ctx the function context
	 * @param negated whether to answer the complement
	 */
	static void compile(LispVal test, WasmLispCompiler.Ctx ctx, boolean negated) {
		if (tryCompile(test, ctx, negated)) {
			return;
		}
		WasmExprCompiler.compileExpr(test, ctx);
		ctx.writer.write(Instruction.REF_IS_NULL);
		if (!negated) {
			ctx.writer.write(Instruction.I32_EQZ);
		}
	}

	/**
	 * Like {@link #compile}, for the shapes with a native i32 truth value; returns
	 * {@code false} having emitted nothing for every other shape.
	 * @param test the test form
	 * @param ctx the function context
	 * @param negated whether to answer the complement
	 * @return whether the test was compiled
	 */
	static boolean tryCompile(LispVal test, WasmLispCompiler.Ctx ctx, boolean negated) {
		if (test instanceof LispTrue || test instanceof LispNil) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128((test instanceof LispTrue) != negated ? 1 : 0);
			return true;
		}
		if (!(test instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
			return false;
		}
		// The names are dispatched exactly as WasmExprCompiler dispatches them, so no
		// user definition can be meant instead; an await inside a test never reaches
		// here (the async if/while compile their test through the state machine).
		List<LispVal> args = cons.toList();
		switch (head.name()) {
			case LispNames.NOT, LispNames.NULL -> {
				if (args.size() != 2) {
					return false;
				}
				compile(args.get(1), ctx, !negated);
				return true;
			}
			case LispNames.CONSP, LispNames.ATOM -> {
				if (args.size() != 2) {
					return false;
				}
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
				if (LispNames.ATOM.equals(head.name()) != negated) {
					ctx.writer.write(Instruction.I32_EQZ);
				}
				return true;
			}
			case LispNames.EQ_GENERAL, LispNames.EQL -> {
				if (args.size() != 3) {
					return false;
				}
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				WasmExprCompiler.compileExpr(args.get(2), ctx);
				if (LispNames.EQL.equals(head.name())) {
					WasmEmitHelper.emitEqlComparison(ctx);
				}
				else {
					WasmEmitHelper.emitEqComparison(ctx);
				}
				if (negated) {
					ctx.writer.write(Instruction.I32_EQZ);
				}
				return true;
			}
			case LispNames.AND, LispNames.OR -> {
				compileChain(args.subList(1, args.size()), LispNames.AND.equals(head.name()), ctx);
				if (negated) {
					ctx.writer.write(Instruction.I32_EQZ);
				}
				return true;
			}
			default -> {
				return WasmComparisonCompiler.tryCompileConditionI32(test, ctx, negated);
			}
		}
	}

	// (and a b c) -> a; if (result i32) [b; if (result i32) c else 0 end] else 0 end;
	// (or a b c) -> a; if (result i32) 1 else [b; if (result i32) 1 else c end] end. An
	// operand is compiled as a test itself, so nothing along the chain is boxed; the
	// control depth is tracked through each arm so a return inside an operand finds its
	// block.
	private static void compileChain(List<LispVal> operands, boolean conjunction, WasmLispCompiler.Ctx ctx) {
		if (operands.isEmpty()) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(conjunction ? 1 : 0);
			return;
		}
		compile(operands.get(0), ctx, false);
		if (operands.size() == 1) {
			return;
		}
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		ctx.wasmCtrlDepth++;
		if (conjunction) {
			compileChain(operands.subList(1, operands.size()), true, ctx);
			ctx.writer.write(Instruction.ELSE);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0);
		}
		else {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(1);
			ctx.writer.write(Instruction.ELSE);
			compileChain(operands.subList(1, operands.size()), false, ctx);
		}
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
	}

}
