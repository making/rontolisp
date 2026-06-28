package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the runtime helper functions that support the memory-backed
 * ({@code :string}/{@code :sexpr}) {@code rontolisp:wasm-export} marshalling: a
 * host-facing bump allocator and a "linear-memory bytes to Lisp string" constructor.
 * Emitted only when a program declares at least one memory-typed export.
 */
final class WasmExportRuntimeBuilder {

	private WasmExportRuntimeBuilder() {
	}

	/**
	 * Builds {@code __ronto_alloc(size i32) -> i32}: a bump allocator over the shared
	 * linear memory heap pointer ({@code HEAP_PTR_ADDR}). Returns the old pointer and
	 * advances it by {@code size}, rounded up to an 8-byte boundary. Exported so a host
	 * can reserve a scratch buffer to write string / s-expression arguments into.
	 * @return the function body bytes (signature {@code (i32) -> i32}, reuses
	 * TYPE_LOOKUP)
	 */
	static byte[] buildAllocBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// param0 = size; local1 = old pointer
		w.write(1);
		w.write(1);
		w.write(Type.I32);
		// old = memory[HEAP_PTR_ADDR]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);
		// memory[HEAP_PTR_ADDR] = (old + size + 7) & ~7
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(7);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(-8);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// return old
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds {@code _export_str_from_mem(ptr i32, len i32) -> (ref null eq)}: copies
	 * {@code len} raw UTF-8 bytes from linear memory into a fresh quoted region on the
	 * heap and returns a {@code TYPE_STRING} struct over it (the internal string
	 * representation stores surrounding {@code "} quotes). Used to box a {@code :string}
	 * argument a host wrote into memory.
	 * @return the function body bytes (signature {@code (i32,i32) -> (ref null eq)},
	 * reuses TYPE_RAT_NEW)
	 */
	static byte[] buildStrFromMemBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int ptr = 0;
		final int len = 1;
		final int dst = 2;
		final int idx = 3;
		// locals: dst (i32), idx (i32)
		w.write(1);
		w.write(2);
		w.write(Type.I32);
		// dst = memory[HEAP_PTR_ADDR]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(dst);
		// memory[dst] = '"'
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(dst);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// idx = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(idx);
		// while (idx < len) memory[dst+1+idx] = memory[ptr+idx]; idx++
		w.write(Instruction.BLOCK);
		w.write(0x40); // void block type
		w.write(Instruction.LOOP);
		w.write(0x40);
		// if idx >= len, break out of block (depth 1)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(idx);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(len);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeSignedLeb128(1);
		// addr = dst + 1 + idx
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(dst);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(idx);
		w.write(Instruction.I32_ADD);
		// value = memory[ptr + idx]
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(ptr);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(idx);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// idx++
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(idx);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(idx);
		// continue loop (depth 0)
		w.write(Instruction.BR);
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// memory[dst+1+len] = '"'
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(dst);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(len);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// memory[HEAP_PTR_ADDR] = dst + len + 2
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(dst);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(len);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// return struct.new TYPE_STRING { dst, len + 2 }
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(dst);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(len);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.END);
		return body.toByteArray();
	}

}
