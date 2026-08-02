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
		final int STR = 0, LEN = 1, START = 2, K = 3, ARR = 4;
		// three i32 locals + one $str_bytes ref (the content read source)
		w.write(2);
		w.writeUnsignedLeb128(3);
		w.write(Type.I32);
		w.writeUnsignedLeb128(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_STR_BYTES);
		// arr = STR.data; len = content length (the name minus its surrounding quotes)
		get(w, STR);
		WasmEmitHelper.emitStrBytesArray(w);
		set(w, ARR);
		get(w, STR);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(1);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		set(w, LEN);
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
		get(w, ARR);
		i32(w, 1);
		get(w, K);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, K);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, K);
		w.write(Instruction.BR);
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// HEAP_PTR is NOT advanced (a stack pop): _str_fresh copies the #:name bytes into
		// a
		// fresh GC array and stamps a unique counter id, so each make-symbol is a
		// distinct
		// uninterned symbol (eq only to itself) even as the scratch offset is reused.
		// return _str_fresh(start, len + 2)
		get(w, START);
		get(w, LEN);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		WasmEmitHelper.emitStrFreshCall(w);
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
		// Copy the string's bytes into linear scratch at HEAP_PTR (not advanced here).
		// The
		// bytes live on the GC heap, so _str_to_mem bridges them into the linear range
		// _intern scans. off = HEAP_PTR ; len = _str_to_mem(str, off) - 2 (bare content).
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		set(w, OFF);
		get(w, STR);
		get(w, OFF);
		WasmEmitHelper.emitStrToMemCall(w);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		set(w, LEN);
		// off = off + 1 (skip the opening quote -> the bare content start)
		get(w, OFF);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, OFF);
		// struct.new string(_intern(off, len), len): _intern compacts the token down to
		// the stable pool at HEAP_PTR on a miss (advancing HEAP_PTR permanently) or
		// returns
		// an existing offset on a hit, so the interned symbol keeps a stable id (eq
		// across
		// occurrences) even though the scratch it was copied into is reused.
		get(w, OFF);
		get(w, LEN);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_INTERN);
		get(w, LEN);
		WasmEmitHelper.emitStrBuildCall(w);
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
		final int SYM = 0, OFF = 1, BIND = 2;
		w.write(2);
		w.writeUnsignedLeb128(1);
		w.write(Type.I32);
		w.writeUnsignedLeb128(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
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
		// bind = _env_lookup(off, GLOBAL_FENV). A binding decides the answer on its own:
		// fmakunbound leaves a TOMBSTONE here (cdr nil) that must SHADOW the compiled
		// registry probed below, or a retired name would answer t again.
		get(w, OFF);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		set(w, BIND);
		get(w, BIND);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		get(w, BIND);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		emitNull(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
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
	 * {@code _fmakunbound(sym) -> (ref null eq)}: installs a TOMBSTONE binding (cdr nil)
	 * for the name in the runtime function namespace {@code GLOBAL_FENV}, which is probed
	 * before the compiled-function registry -- so every LATE-bound reference sees the
	 * name undefined again, while a call site the compiler already bound directly keeps
	 * working (eager compilation cannot be undone). Returns the symbol, like CL.
	 */
	static byte[] buildFmakunbound() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int SYM = 0, OFF = 1, BIND = 2;
		w.write(2);
		w.writeUnsignedLeb128(1);
		w.write(Type.I32);
		w.writeUnsignedLeb128(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		// nil or a non-string value names no function: hand it back untouched
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
		get(w, SYM);
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
		// bind = _env_lookup(off, GLOBAL_FENV)
		get(w, OFF);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		set(w, BIND);
		get(w, BIND);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		// $fenv = cons(cons(sym, nil), $fenv)
		get(w, SYM);
		emitNull(w);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
		w.write(Instruction.ELSE);
		// bind.cdr = nil
		get(w, BIND);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		emitNull(w);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		w.write(Instruction.END);
		get(w, SYM);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * {@code _set_symbol_function(sym, value) -> (ref null eq)}: the write-side twin of
	 * {@code _fmakunbound} -- stores {@code value} into the name's {@code GLOBAL_FENV}
	 * binding (mutating an existing cell, else prepending a fresh one), so every
	 * LATE-bound reference (and the setf-only-alias forwarder's {@code _fenv_function}
	 * probe) resolves to it. Returns the value, the {@code setf} result. A nil or
	 * non-string name is lenient (the value is handed back untouched), like
	 * {@code _fmakunbound}'s non-name tolerance.
	 */
	static byte[] buildSetSymbolFunction() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int SYM = 0, VALUE = 1, OFF = 2, BIND = 3;
		w.write(2);
		w.writeUnsignedLeb128(1);
		w.write(Type.I32);
		w.writeUnsignedLeb128(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		get(w, SYM);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		get(w, VALUE);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		get(w, SYM);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		get(w, VALUE);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// off = struct.get string 0 (the canonical string-table offset _env_lookup keys
		// on; a quoted literal name is interned by construction)
		get(w, SYM);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(0);
		set(w, OFF);
		// bind = _env_lookup(off, GLOBAL_FENV)
		get(w, OFF);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		set(w, BIND);
		get(w, BIND);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		// $fenv = cons(cons(sym, value), $fenv)
		get(w, SYM);
		get(w, VALUE);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
		w.write(Instruction.ELSE);
		// bind.cdr = value
		get(w, BIND);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		get(w, VALUE);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		w.write(Instruction.END);
		get(w, VALUE);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * {@code _fenv_function(sym) -> (ref null eq)}: the name's {@code GLOBAL_FENV}
	 * binding value. The compiled-function registry is deliberately NOT probed -- the
	 * caller is the setf-only-alias forwarder defun registered under the very name. A
	 * miss (no binding, or an {@code _fmakunbound} tombstone) traps ({@code unreachable},
	 * the {@code %error} convention -- CL's undefined-function for a call before the
	 * assignment ran).
	 */
	static byte[] buildFenvFunction() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int SYM = 0, OFF = 1, VALUE = 2;
		w.write(2);
		w.writeUnsignedLeb128(1);
		w.write(Type.I32);
		w.writeUnsignedLeb128(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		get(w, SYM);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		get(w, SYM);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		get(w, SYM);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(0);
		set(w, OFF);
		get(w, OFF);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		set(w, VALUE);
		get(w, VALUE);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		// value = bind.cdr; a tombstone's nil traps like a missing binding
		get(w, VALUE);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		set(w, VALUE);
		get(w, VALUE);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		get(w, VALUE);
		w.write(Instruction.END);
		return body.toByteArray();
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
		get(w, symSlot);
		WasmEmitHelper.emitStrBytesArray(w);
		i32(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		i32(w, ':');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		onSelf.run();
		w.write(Instruction.END);
	}

	private static void emitTrue(WasmWriter w, int tOffset) {
		i32(w, tOffset);
		i32(w, 1);
		WasmEmitHelper.emitStrBuildCall(w);
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
