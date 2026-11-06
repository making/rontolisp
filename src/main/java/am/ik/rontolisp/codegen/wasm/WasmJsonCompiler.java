package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles {@code (rontolisp::%json-decode string null-symbol)},
 * {@code (rontolisp::%json-encode value null-symbol other key-name)} and
 * {@code (rontolisp::%json-scan document size path mode)}: a call of the module's JSON
 * codec ({@link WasmJsonRuntimeBuilder}) or span scan
 * ({@link WasmJsonSpanRuntimeBuilder}), which the pre-pass gave the module because the
 * program names it.
 */
final class WasmJsonCompiler {

	private WasmJsonCompiler() {
	}

	static void compile(String member, LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		boolean decode = LispNames.JSON_DECODE_INTERNAL.equals(member);
		boolean scan = LispNames.JSON_SCAN_INTERNAL.equals(member);
		int expected = decode ? 2 : 4;
		if (args.size() - 1 != expected) {
			throw new UnsupportedOperationException(
					"rontolisp::" + member + " expects " + expected + " arguments, got " + (args.size() - 1));
		}
		int base = scan ? ctx.jsonSpanFuncBase : ctx.jsonCodecFuncBase;
		if (base < 0) {
			throw new IllegalStateException(
					"rontolisp::" + member + " reached a module without the JSON " + (scan ? "scan" : "codec"));
		}
		for (int i = 1; i < args.size(); i++) {
			WasmExprCompiler.compileExpr(args.get(i), ctx);
		}
		if (decode) {
			// a number with a fraction or an exponent scales by (expt 10.0 n)
			ctx.fdlibm(WasmFdlibmRuntimeBuilder.Fn.POW);
		}
		else if (!scan) {
			// other and key-name are called through the one-argument dispatcher
			ctx.indirectCallArities.add(1);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(base + (scan ? WasmJsonSpanRuntimeBuilder.SCAN
				: decode ? WasmJsonRuntimeBuilder.DECODE : WasmJsonRuntimeBuilder.ENCODE));
	}

}
