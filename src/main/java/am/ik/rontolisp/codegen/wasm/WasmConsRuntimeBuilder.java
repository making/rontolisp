package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.WasmWriter;

/**
 * Builds {@code _car} / {@code _cdr} ({@code FUNC_CAR} / {@code FUNC_CDR}): the
 * nil-passing cons field readers, {@code ((ref null eq) list) -> (ref null eq)}. The body
 * is exactly the shape a {@code car}/{@code cdr} site emits inline -- nil is returned
 * as-is, a cons yields its field, anything else traps on the cast -- so a site that calls
 * it instead answers byte for byte what the inline spelling answered
 * ({@code .kb/cons-access-runtime.md}).
 */
final class WasmConsRuntimeBuilder {

	private WasmConsRuntimeBuilder() {
	}

	/**
	 * The body of {@code _car} (field 0) or {@code _cdr} (field 1); signature
	 * {@code TYPE_CALLABLE_BASE + 0}.
	 * @param field the cons field the function reads
	 * @return the code entry
	 */
	static byte[] buildFieldBody(int field) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // no locals: the one parameter is the list
		WasmEmitHelper.emitInlineConsField(w, 0, field);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/** Emits {@code call FUNC_CAR} / {@code call FUNC_CDR} for the value on the stack. */
	static void emitCall(WasmWriter w, int field) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(field == 0 ? WasmLispCompiler.FUNC_CAR : WasmLispCompiler.FUNC_CDR);
	}

}
