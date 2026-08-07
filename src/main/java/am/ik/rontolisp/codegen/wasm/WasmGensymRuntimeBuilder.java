package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the {@code _gensym(prefix_off, prefix_len) -> (ref null eq)} runtime function
 * (type TYPE_RAT_NEW). It bumps the counter word at {@code GENSYM_CTR_ADDR}, writes the
 * prefix bytes (the call site passes the full {@code "#:<prefix>"} text as an interned
 * string-table range) followed by the counter's decimal digits into a fresh heap string,
 * and returns a string struct. The result carries no surrounding quotes, so it is a
 * symbol at runtime.
 */
final class WasmGensymRuntimeBuilder {

	private WasmGensymRuntimeBuilder() {
	}

	static byte[] build() {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		final WasmWriter w = new WasmWriter(body);
		final int OFF = 0, LEN = 1, N = 2, START = 3, CUR = 4, T = 5, D = 6, K = 7;
		// six extra i32 locals
		w.write(1);
		w.writeUnsignedLeb128(6);
		w.write(Type.I32);
		// n = mem[GENSYM_CTR_ADDR] + 1; mem[GENSYM_CTR_ADDR] = n
		i32(w, WasmLispCompiler.GENSYM_CTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, N);
		i32(w, WasmLispCompiler.GENSYM_CTR_ADDR);
		get(w, N);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// start = mem[HEAP_PTR_ADDR]; ensure start + len + 10 (max i32 digits) fits
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		set(w, START);
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			get(w, START);
			get(w, LEN);
			w.write(Instruction.I32_ADD);
			i32(w, 10);
			w.write(Instruction.I32_ADD);
		});
		// for k in 0..len: mem[start+k] = mem[off+k]
		i32(w, 0);
		set(w, K);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, K);
		get(w, LEN);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		get(w, START);
		get(w, K);
		w.write(Instruction.I32_ADD);
		get(w, OFF);
		get(w, K);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, K);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, K);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// cur = start + len
		get(w, START);
		get(w, LEN);
		w.write(Instruction.I32_ADD);
		set(w, CUR);
		// count the decimal digits of n (n >= 1): t = n; d = 0; do { d++; t /= 10 }
		// while t != 0
		get(w, N);
		set(w, T);
		i32(w, 0);
		set(w, D);
		w.write(Instruction.LOOP, 0x40);
		get(w, D);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, D);
		get(w, T);
		i32(w, 10);
		w.write(Instruction.I32_DIV_U);
		set(w, T);
		get(w, T);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // loop
		// write digits backwards: t = n; k = cur + d - 1;
		// do { mem[k] = '0' + t % 10; t /= 10; k-- } while t != 0
		get(w, N);
		set(w, T);
		get(w, CUR);
		get(w, D);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, K);
		w.write(Instruction.LOOP, 0x40);
		get(w, K);
		get(w, T);
		i32(w, 10);
		w.write(Instruction.I32_REM_U);
		i32(w, '0');
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, T);
		i32(w, 10);
		w.write(Instruction.I32_DIV_U);
		set(w, T);
		get(w, K);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, K);
		get(w, T);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // loop
		// cur += d. HEAP_PTR is NOT advanced (a stack pop): _str_fresh copies the #:g<n>
		// bytes into a fresh GC array with a unique counter id (each gensym is a distinct
		// uninterned symbol), so the scratch region is reused for the next build.
		get(w, CUR);
		get(w, D);
		w.write(Instruction.I32_ADD);
		set(w, CUR);
		// return _str_fresh(start, cur - start)
		get(w, START);
		get(w, CUR);
		get(w, START);
		w.write(Instruction.I32_SUB);
		WasmEmitHelper.emitStrFreshCall(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void get(WasmWriter w, int local) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(local);
	}

	private static void set(WasmWriter w, int local) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(local);
	}

}
