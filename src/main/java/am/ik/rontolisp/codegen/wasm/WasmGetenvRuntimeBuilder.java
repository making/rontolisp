package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the {@code _getenv} runtime helper for WASM:
 * {@code ((ref null eq) name) -> (ref null eq)}. It scans the WASI environ buffer (filled
 * by {@code environ_sizes_get}/{@code environ_get}, which are bound to the real host in
 * Preview 1 and to {@code wasi:cli/environment} through the adapter in component mode)
 * for {@code NAME=}, and returns the value as a fresh heap string (with the surrounding
 * quotes rontolisp strings carry), or {@code nil} when the variable is unset.
 */
final class WasmGetenvRuntimeBuilder {

	private static final int NAME = 0; // param: the variable name (a string struct)

	// slot 1 is spare (the old NAME_OFF linear content offset, now read via NAME_ARR).

	private static final int NAME_LEN = 2;

	private static final int COUNT = 3;

	private static final int I = 4;

	private static final int P = 5;

	private static final int J = 6; // also reused as the copy index / value length

	private static final int OK = 7;

	private static final int VAL_START = 8;

	private static final int VAL_END = 9;

	private static final int HEAP = 10;

	private static final int NAME_ARR = 11; // the name string's $str_bytes data array

	private static final int QUOTE = 0x22;

	private static final int EQUALS = 0x3d; // '='

	private WasmGetenvRuntimeBuilder() {
	}

	static byte[] build() {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		final WasmWriter w = new WasmWriter(body);

		// 10 i32 locals (indices 1..10; index 0 is the name parameter) + one $str_bytes
		// ref
		// (index 11, the name string's data array).
		w.write(2);
		w.writeUnsignedLeb128(10);
		w.write(Type.I32);
		w.writeUnsignedLeb128(1);
		w.writeRefType(true, WasmLispCompiler.TYPE_STR_BYTES);

		// nameArr = the name string's data array ; nameLen = length - 2 (strip the
		// quotes).
		getLocal(w, NAME);
		WasmEmitHelper.emitStrBytesArray(w);
		setLocal(w, NAME_ARR);
		getLocal(w, NAME);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 1);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		setLocal(w, NAME_LEN);

		// environ_sizes_get(ENV_COUNT_ADDR, ENV_BUFSIZE_ADDR); environ_get(ENV_PTRS_ADDR,
		// ENV_BUF_ADDR)
		i32(w, WasmLispCompiler.ENV_COUNT_ADDR);
		i32(w, WasmLispCompiler.ENV_BUFSIZE_ADDR);
		call(w, WasmLispCompiler.FUNC_ENVIRON_SIZES_GET);
		w.write(Instruction.DROP);
		i32(w, WasmLispCompiler.ENV_PTRS_ADDR);
		i32(w, WasmLispCompiler.ENV_BUF_ADDR);
		call(w, WasmLispCompiler.FUNC_ENVIRON_GET);
		w.write(Instruction.DROP);

		i32(w, WasmLispCompiler.ENV_COUNT_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, COUNT);
		i32(w, 0);
		setLocal(w, I);
		i32(w, -1);
		setLocal(w, VAL_START); // sentinel: not found

		// for each entry: compare the name and check for '='
		w.write(Instruction.BLOCK, 0x40); // $done
		w.write(Instruction.LOOP, 0x40); // $l
		getLocal(w, I);
		getLocal(w, COUNT);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1); // br to $done
		// p = load(ENV_PTRS_ADDR + i*4)
		i32(w, WasmLispCompiler.ENV_PTRS_ADDR);
		getLocal(w, I);
		i32(w, 4);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, P);
		// j = 0 ; ok = 1
		i32(w, 0);
		setLocal(w, J);
		i32(w, 1);
		setLocal(w, OK);
		w.write(Instruction.BLOCK, 0x40); // $cmpDone
		w.write(Instruction.LOOP, 0x40); // $cmp
		getLocal(w, J);
		getLocal(w, NAME_LEN);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1); // br to $cmpDone
		// if p[j] != nameArr[1 + j]: ok = 0 ; break (name content past the opening quote)
		getLocal(w, P);
		getLocal(w, J);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		getLocal(w, NAME_ARR);
		i32(w, 1);
		getLocal(w, J);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		setLocal(w, OK);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(2); // br to $cmpDone (out of if + loop)
		w.write(Instruction.END); // end if
		getLocal(w, J);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, J);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0); // br to $cmp
		w.write(Instruction.END); // end $cmp loop
		w.write(Instruction.END); // end $cmpDone block
		// if ok && p[nameLen] == '=': valStart = p + nameLen + 1 ; break
		getLocal(w, OK);
		getLocal(w, P);
		getLocal(w, NAME_LEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, EQUALS);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		getLocal(w, P);
		getLocal(w, NAME_LEN);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, VAL_START);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(2); // br to $done
		w.write(Instruction.END); // end if
		getLocal(w, I);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, I);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0); // br to $l
		w.write(Instruction.END); // end $l loop
		w.write(Instruction.END); // end $done block

		// if not found: return nil
		getLocal(w, VAL_START);
		i32(w, -1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.ELSE);

		// valEnd = strlen(valStart)
		getLocal(w, VAL_START);
		setLocal(w, VAL_END);
		w.write(Instruction.BLOCK, 0x40); // $strDone
		w.write(Instruction.LOOP, 0x40); // $str
		getLocal(w, VAL_END);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1); // br to $strDone
		getLocal(w, VAL_END);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, VAL_END);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // end $str loop
		w.write(Instruction.END); // end $strDone block
		// valLen = valEnd - valStart (reuse J)
		getLocal(w, VAL_END);
		getLocal(w, VAL_START);
		w.write(Instruction.I32_SUB);
		setLocal(w, J);
		// heap = memory[HEAP_PTR_ADDR]
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, HEAP);
		// heap[0] = '"'
		getLocal(w, HEAP);
		i32(w, QUOTE);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// copy valLen bytes: heap[1 + k] = valStart[k] ; k reuses OK
		i32(w, 0);
		setLocal(w, OK);
		w.write(Instruction.BLOCK, 0x40); // $copyDone
		w.write(Instruction.LOOP, 0x40); // $copy
		getLocal(w, OK);
		getLocal(w, J);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1); // br to $copyDone
		getLocal(w, HEAP);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		getLocal(w, OK);
		w.write(Instruction.I32_ADD);
		getLocal(w, VAL_START);
		getLocal(w, OK);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		getLocal(w, OK);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, OK);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // end $copy loop
		w.write(Instruction.END); // end $copyDone block
		// heap[1 + valLen] = '"'
		getLocal(w, HEAP);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		getLocal(w, J);
		w.write(Instruction.I32_ADD);
		i32(w, QUOTE);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// HEAP_PTR is NOT advanced (a stack pop): _str_fresh copies the value into a
		// fresh
		// GC array with a counter id, so the scratch region is reused for the next build.
		// return _str_fresh(heap, valLen + 2)
		getLocal(w, HEAP);
		getLocal(w, J);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		WasmEmitHelper.emitStrFreshCall(w);

		w.write(Instruction.END); // end if/else
		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	private static void getLocal(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void setLocal(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void call(WasmWriter w, int func) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(func);
	}

	private static void refCast(WasmWriter w, int heapType) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(heapType);
	}

	private static void structGet(WasmWriter w, int type, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(type);
		w.writeUnsignedLeb128(field);
	}

	private static void structNew(WasmWriter w, int type) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(type);
	}

}
