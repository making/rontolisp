package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.ArgumentOrder;
import am.ik.rontolisp.compiler.ComplexCapability;
import am.ik.wasm.Instruction;

/**
 * The operands of an f64 site in a module whose program may observe a complex
 * (`.kb/wasm-complex.md`, "A complex beside a float literal"). A float literal routes
 * {@code (* 2.0 z)}, {@code (abs (* 2.0 z))} and {@code (= (* 2.0 z) 1.0)} onto raw
 * {@code f64} instructions over {@code _as_f64}, which rejects a {@code TYPE_COMPLEX}, so
 * a complex a variable holds never reached the helpers that answer it. Here a site
 * evaluates every operand into a temporary first -- left to right, as the interpreter
 * evaluates an operation's arguments before applying it -- tests the ones a variable or a
 * call produced for a complex, and runs its raw form when none is one and its generic
 * form, whose helpers answer the complex, when one is. An inner float-literal operation
 * is an operand like any other call: it boxed its result before, and still does.
 */
final class WasmFloatOperands {

	private WasmFloatOperands() {
	}

	/**
	 * Whether an f64 site over these operand forms takes this class's emission: a module
	 * whose program may observe a complex, and an operand that may evaluate to one
	 * ({@link ComplexCapability#mayYieldComplex}). Every other site keeps its raw
	 * emission byte for byte.
	 * @param operands the operand forms
	 * @param ctx the compile context
	 * @return whether the site must find a complex at run time
	 */
	static boolean guards(List<LispVal> operands, WasmLispCompiler.Ctx ctx) {
		if (ctx.complexBlock == null) {
			return false;
		}
		for (LispVal operand : operands) {
			if (mayYieldComplex(operand)) {
				return true;
			}
		}
		return false;
	}

	private static boolean mayYieldComplex(LispVal operand) {
		return ComplexCapability.mayYieldComplex(operand, name -> false);
	}

	/**
	 * Evaluates the operand forms left to right into temporaries; a constant is left
	 * where its readers compile it.
	 * @param forms the operand forms
	 * @param ctx the compile context
	 * @return the evaluated operands
	 */
	static Operands evaluate(List<LispVal> forms, WasmLispCompiler.Ctx ctx) {
		List<Operand> operands = new ArrayList<>(forms.size());
		for (LispVal form : forms) {
			if (form instanceof LispDouble || form instanceof LispInteger || ArgumentOrder.isOrderIndependent(form)) {
				operands.add(new Operand(form, -1, false));
				continue;
			}
			WasmExprCompiler.compileExpr(form, ctx);
			int slot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
			operands.add(new Operand(form, slot, mayYieldComplex(form)));
		}
		return new Operands(operands);
	}

	/**
	 * One operand: a constant ({@code slot} -1), or the temporary its value was evaluated
	 * into, {@code tested} when it may hold a complex.
	 */
	private record Operand(LispVal form, int slot, boolean tested) {
	}

	/** A site's operands, evaluated. */
	static final class Operands {

		private final List<Operand> operands;

		private Operands(List<Operand> operands) {
			this.operands = operands;
		}

		int size() {
			return this.operands.size();
		}

		/**
		 * Pushes the i32 truth of "an operand holds a complex", for the {@code if} that
		 * picks the site's generic form.
		 * @param ctx the compile context
		 */
		void emitHoldsComplex(WasmLispCompiler.Ctx ctx) {
			boolean first = true;
			for (Operand operand : this.operands) {
				if (!operand.tested()) {
					continue;
				}
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(operand.slot());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
				if (!first) {
					ctx.writer.write(Instruction.I32_OR);
				}
				first = false;
			}
		}

		/**
		 * Pushes operand {@code i} as an {@code f64}: a number literal as its constant,
		 * anything else through {@code _as_f64} under the site's operator.
		 * @param i the operand's index
		 * @param ctx the compile context
		 */
		void pushRaw(int i, WasmLispCompiler.Ctx ctx) {
			Operand operand = this.operands.get(i);
			if (operand.form() instanceof LispDouble d) {
				f64Const(ctx, d.value());
				return;
			}
			if (operand.form() instanceof LispInteger integer) {
				f64Const(ctx, integer.value());
				return;
			}
			pushBoxed(i, ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
		}

		/**
		 * Pushes operand {@code i} as the generic path sees it: the boxed value.
		 * @param i the operand's index
		 * @param ctx the compile context
		 */
		void pushBoxed(int i, WasmLispCompiler.Ctx ctx) {
			Operand operand = this.operands.get(i);
			if (operand.slot() < 0) {
				WasmExprCompiler.compileExpr(operand.form(), ctx);
				return;
			}
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(operand.slot());
		}

		private static void f64Const(WasmLispCompiler.Ctx ctx, double value) {
			ctx.writer.write(Instruction.F64_CONST);
			ctx.writer.writeF64(value);
		}

	}

}
