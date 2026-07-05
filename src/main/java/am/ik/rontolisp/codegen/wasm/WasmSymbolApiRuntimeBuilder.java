package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the five symbol-runtime-API helper functions, all typed
 * {@code ((ref null eq)) -> (ref null eq)} (TYPE_CALLABLE_BASE):
 *
 * <ul>
 * <li>{@code _make_symbol(str)}: copies {@code #:} + the string content (the byte range
 * between the surrounding quotes) into a fresh heap string -- an uninterned symbol by
 * construction, since its offset matches no string-table entry.</li>
 * <li>{@code _intern_sym(str)}: canonicalizes the content range through {@code _intern}
 * so the resulting symbol's offset compares equal to literals in the eval runtime's
 * offset-based environment lookups. Needs the real {@code _intern} body (the compiler
 * forces it when the program calls {@code intern}).</li>
 * <li>{@code _boundp(sym)} / {@code _symbol_value(sym)}: nil/t/keywords are self-bound;
 * anything else probes the eval global environment mirror ({@code GLOBAL_ENV}) via
 * {@code _env_lookup}. An unbound {@code symbol-value} traps ({@code unreachable}, the
 * same lite error signaling as {@code %error}).</li>
 * <li>{@code _fboundp(sym)}: probes the runtime function namespace ({@code GLOBAL_FENV})
 * then the compiled-function registry ({@code _lookup}); built-in macros and special
 * forms exist only at compile time, so a computed argument sees functions only (literal
 * calls fold in {@link WasmSymbolApiCompiler}).</li>
 * </ul>
 *
 * The callers force {@code usesEval} so {@code _env_lookup}/{@code _lookup} have real
 * bodies; when none of these built-ins appear the helpers are still emitted (fixed
 * function indices) but only reference stubs.
 */
final class WasmSymbolApiRuntimeBuilder {

	private WasmSymbolApiRuntimeBuilder() {
	}

	/** {@code _make_symbol(str) -> (ref null eq)}: heap copy of {@code #:} + content. */
	static byte[] buildMakeSymbol() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int STR = 0, OFF = 1, LEN = 2, START = 3, K = 4;
		// four extra i32 locals
		w.write(1);
		w.writeUnsignedLeb128(4);
		w.write(Type.I32);
		// off = content offset (skip the opening quote), len = content length
		emitContentRange(w, STR, OFF, LEN);
		// start = mem[HEAP_PTR_ADDR]; ensure start + len + 2 fits
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		set(w, START);
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			get(w, START);
			get(w, LEN);
			w.write(Instruction.I32_ADD);
			i32(w, 2);
			w.write(Instruction.I32_ADD);
		});
		// mem[start] = '#'; mem[start+1] = ':'
		get(w, START);
		i32(w, '#');
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, START);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		i32(w, ':');
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// for k in 0..len: mem[start+2+k] = mem[off+k]
		i32(w, 0);
		set(w, K);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, K);
		get(w, LEN);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeSignedLeb128(1);
		get(w, START);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
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
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// mem[HEAP_PTR_ADDR] = start + len + 2
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		get(w, START);
		get(w, LEN);
		w.write(Instruction.I32_ADD);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// return struct.new string(start, len + 2)
		get(w, START);
		get(w, LEN);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * {@code _intern_sym(str) -> (ref null eq)}: canonical symbol via {@code _intern}.
	 */
	static byte[] buildInternSym() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int STR = 0, OFF = 1, LEN = 2;
		w.write(1);
		w.writeUnsignedLeb128(2);
		w.write(Type.I32);
		emitContentRange(w, STR, OFF, LEN);
		// struct.new string(_intern(off, len), len)
		get(w, OFF);
		get(w, LEN);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_INTERN);
		get(w, LEN);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * {@code _boundp(sym) -> (ref null eq)}.
	 * @param tOffset the string-table offset of the symbol {@code t}
	 */
	static byte[] buildBoundp(int tOffset) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int SYM = 0, OFF = 1;
		w.write(1);
		w.writeUnsignedLeb128(1);
		w.write(Type.I32);
		emitSelfBoundChecks(w, SYM, OFF, tOffset, () -> {
			emitTrue(w, tOffset);
			w.write(Instruction.RETURN);
		}, () -> {
			emitTrue(w, tOffset);
			w.write(Instruction.RETURN);
		}, () -> {
			emitNull(w);
			w.write(Instruction.RETURN);
		});
		// _env_lookup(off, GLOBAL_ENV) != null -> t
		get(w, OFF);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_ENV);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		emitNull(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		emitTrue(w, tOffset);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * {@code _symbol_value(sym) -> (ref null eq)}: an unbound variable traps.
	 * @param tOffset the string-table offset of the symbol {@code t}
	 */
	static byte[] buildSymbolValue(int tOffset) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int SYM = 0, OFF = 1;
		w.write(1);
		w.writeUnsignedLeb128(1);
		w.write(Type.I32);
		emitSelfBoundChecks(w, SYM, OFF, tOffset, () -> {
			emitNull(w);
			w.write(Instruction.RETURN);
		}, () -> {
			get(w, SYM);
			w.write(Instruction.RETURN);
		}, () -> w.write(Instruction.UNREACHABLE));
		// pair = _env_lookup(off, GLOBAL_ENV); null -> trap; else (cdr pair)
		get(w, OFF);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_ENV);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		set(w, SYM); // reuse the SYM slot for the binding pair
		get(w, SYM);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		get(w, SYM);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/** {@code _fboundp(sym) -> (ref null eq)}: GLOBAL_FENV, then the defun registry. */
	static byte[] buildFboundp(int tOffset) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int SYM = 0, OFF = 1;
		w.write(1);
		w.writeUnsignedLeb128(1);
		w.write(Type.I32);
		// nil or a non-string value is not a function name
		get(w, SYM);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		emitNull(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		get(w, SYM);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		emitNull(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// off = struct.get string 0
		get(w, SYM);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(0);
		set(w, OFF);
		// _env_lookup(off, GLOBAL_FENV) != null -> t
		get(w, OFF);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		emitTrue(w, tOffset);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// _lookup(off) != -1 -> t
		get(w, OFF);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_LOOKUP);
		i32(w, -1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		emitTrue(w, tOffset);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		emitNull(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Emits {@code off = content offset, len = content length} for the string struct in
	 * {@code strSlot} (skipping the surrounding quotes).
	 */
	private static void emitContentRange(WasmWriter w, int strSlot, int offSlot, int lenSlot) {
		get(w, strSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(0);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, offSlot);
		get(w, strSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(1);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		set(w, lenSlot);
	}

	/**
	 * Emits the shared boundp/symbol-value prologue: {@code onNil} runs for nil,
	 * {@code onSelf} for {@code t} and keywords, {@code onOther} for a non-string value;
	 * each must leave the function (return or trap). Falls through with the symbol's
	 * string-table offset stored in {@code offSlot}.
	 */
	private static void emitSelfBoundChecks(WasmWriter w, int symSlot, int offSlot, int tOffset, Runnable onNil,
			Runnable onSelf, Runnable onOther) {
		get(w, symSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		onNil.run();
		w.write(Instruction.END);
		get(w, symSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		onOther.run();
		w.write(Instruction.END);
		// off = struct.get string 0
		get(w, symSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(0);
		set(w, offSlot);
		// the symbol t (shared literal offset) is self-bound
		get(w, offSlot);
		i32(w, tOffset);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		onSelf.run();
		w.write(Instruction.END);
		// a keyword (first content byte ':') is self-bound
		get(w, offSlot);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, ':');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		onSelf.run();
		w.write(Instruction.END);
	}

	private static void emitTrue(WasmWriter w, int tOffset) {
		i32(w, tOffset);
		i32(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
	}

	private static void emitNull(WasmWriter w) {
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
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
