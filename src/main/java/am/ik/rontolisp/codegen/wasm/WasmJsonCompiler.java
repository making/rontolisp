package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles {@code (rontolisp::%json-decode string null-symbol)} and
 * {@code (rontolisp::%json-encode value null-symbol other key-name)}: a call of the
 * module's JSON codec ({@link WasmJsonRuntimeBuilder}), which the pre-pass gave the
 * module because the program names one of them.
 */
final class WasmJsonCompiler {

	private WasmJsonCompiler() {
	}

	static void compile(String member, LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		boolean decode = LispNames.JSON_DECODE_INTERNAL.equals(member);
		int expected = decode ? 2 : 4;
		if (args.size() - 1 != expected) {
			throw new UnsupportedOperationException(
					"rontolisp::" + member + " expects " + expected + " arguments, got " + (args.size() - 1));
		}
		if (ctx.jsonCodecFuncBase < 0) {
			throw new IllegalStateException("rontolisp::" + member + " reached a module without the JSON codec");
		}
		for (int i = 1; i < args.size(); i++) {
			WasmExprCompiler.compileExpr(args.get(i), ctx);
		}
		if (decode) {
			// a number with a fraction or an exponent scales by (expt 10.0 n)
			ctx.fdlibm(WasmFdlibmRuntimeBuilder.Fn.POW);
		}
		else {
			// other and key-name are called through the one-argument dispatcher
			ctx.indirectCallArities.add(1);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(
				ctx.jsonCodecFuncBase + (decode ? WasmJsonRuntimeBuilder.DECODE : WasmJsonRuntimeBuilder.ENCODE));
	}

}
