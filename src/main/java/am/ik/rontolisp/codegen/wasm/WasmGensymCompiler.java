package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code gensym} built-in function: interns the full {@code "#:<prefix>"}
 * text into the string table and calls the {@code _gensym} runtime function with its
 * offset/length. The optional prefix must be a literal string so the interned text is
 * known at compile time (same design as {@code open}'s literal {@code :direction}).
 */
final class WasmGensymCompiler {

	private WasmGensymCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() > 2) {
			throw new UnsupportedOperationException(LispNames.GENSYM + " expects at most 1 argument: " + cons.print());
		}
		if (args.size() == 2 && args.get(1) instanceof LispInteger n) {
			// CL's other gensym shape: a non-negative integer IS the suffix (under the
			// default "G" prefix) and never touches the counter -- a plain compile-time
			// constant symbol, like a literal string/symbol elsewhere, never routed
			// through FUNC_GENSYM (which always appends AND advances the counter).
			if (n.value() < 0) {
				throw new UnsupportedOperationException(
						LispNames.GENSYM + " suffix must be a non-negative integer: " + cons.print());
			}
			WasmEmitHelper.compileStringLiteral("#:G" + n.value(), ctx);
			return;
		}
		if (args.size() == 2 && !(args.get(1) instanceof LispString)) {
			// A computed prefix: the shared string-construction lowering (the interned
			// prefix text below is a compile-time constant, so it has no place here).
			WasmExprCompiler.compileExpr(am.ik.rontolisp.macro.LispMacroExpander.expandComputedGensym(args.get(1)),
					ctx);
			return;
		}
		String prefix = args.size() == 2 ? ((LispString) args.get(1)).value() : "G";
		WasmLispCompiler.StringTable.StringEntry entry = ctx.stringTable.addString("#:" + prefix);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(entry.offset());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(entry.length());
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_GENSYM);
	}

}
