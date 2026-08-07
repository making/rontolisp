package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds {@code _plist_get((ref null eq) plist, i32 keyOffset) -> (ref null eq)}
 * ({@code FUNC_PLIST_GET}): walks a property list, comparing each keyword key's interned
 * string offset against {@code keyOffset}, and returns the following value (or nil). The
 * component import compiler calls it to lower a record parameter written as a keyword
 * plist.
 */
final class WasmPlistRuntimeBuilder {

	private WasmPlistRuntimeBuilder() {
	}

	/**
	 * {@code _plist_get((ref null eq) plist, i32 keyOffset) -> (ref null eq)} (type
	 * TYPE_OPEN).
	 */
	static byte[] buildPlistGet() {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		final WasmWriter w = new WasmWriter(body);
		final int CUR = 0, KEY_OFF = 1; // reuse the plist param as the cursor
		w.write(0); // no extra locals
		w.write(Instruction.LOOP, 0x40);
		// if cursor is null: return nil
		getLocal(w, CUR);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// if car(cursor).offset == keyOffset: return car(cdr(cursor))
		car(w, CUR);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 0);
		getLocal(w, KEY_OFF);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, CUR);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1); // cdr
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0); // car(cdr) = value
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// cursor = cdr(cdr(cursor))
		getLocal(w, CUR);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, CUR);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // loop (never falls through)
		w.write(0x00); // unreachable
		w.write(Instruction.END);
		return body.toByteArray();
	}

	private static void car(WasmWriter w, int slot) {
		getLocal(w, slot);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
	}

	private static void getLocal(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void setLocal(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(slot);
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

}
