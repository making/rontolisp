package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
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
		String prefix = "g";
		if (args.size() == 2) {
			if (!(args.get(1) instanceof LispString s)) {
				throw new UnsupportedOperationException(
						LispNames.GENSYM + " prefix must be a literal string: " + cons.print());
			}
			prefix = s.value();
		}
		WasmLispCompiler.StringTable.StringEntry entry = ctx.stringTable.addString("#:" + prefix);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(entry.offset());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(entry.length());
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_GENSYM);
	}

}
