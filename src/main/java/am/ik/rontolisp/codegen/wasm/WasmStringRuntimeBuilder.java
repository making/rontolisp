package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds WASM bytecode for the string runtime helpers that produce or compare strings:
 * case conversion ({@code string-upcase} / {@code string-downcase} /
 * {@code string-capitalize}), {@code subseq}, the equality predicates ({@code string=} /
 * {@code string-equal}) and the trim family ({@code string-trim} /
 * {@code string-left-trim} / {@code string-right-trim}).
 *
 * <p>
 * Runtime strings are {@code TYPE_STRING} structs whose bytes in linear memory are
 * wrapped in surrounding quotes ({@code "abc"}). Producing functions copy the content
 * between the quotes into a fresh heap string (allocated at {@code HEAP_PTR_ADDR}) and
 * re-wrap it in quotes. Case handling is ASCII-only.
 */
final class WasmStringRuntimeBuilder {

	private static final int QUOTE = 0x22;

	private static final int BACKSLASH = 0x5C;

	// Fold modes for the shared code-point build core.
	private static final int UPCASE = 1;

	private static final int DOWNCASE = 2;

	private static final int CAPITALIZE = 3;

	private WasmStringRuntimeBuilder() {
	}

	/**
	 * Builds {@code _str_build} (FUNC_STR_BUILD): given a linear byte offset and a byte
	 * length, allocates a {@code $str_bytes} GC array of that length, copies
	 * {@code linear[off..off+len)} into it, and returns a {@code TYPE_STRING} {@code {id
	 * = off, len, data = arr}}.
	 *
	 * <p>
	 * This is the sole constructor for every compiled string/symbol value. It has the
	 * same {@code (i32,i32)->ref} stack shape the old two-field
	 * {@code struct.new TYPE_STRING} had (callers push {@code off/ptr} then {@code len}),
	 * so migrating a build site is a mechanical swap of the {@code struct.new} for
	 * {@code call FUNC_STR_BUILD} -- yet a string's bytes now live on the GC heap. The
	 * linear source is the static/intern data segment for interned names, or a transient
	 * builder scratch for runtime strings; the {@code id} keeps being that source offset
	 * (so identity/{@code eq} are unchanged).
	 * @return the function body
	 */
	static byte[] buildStrBuildBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: off = 0, len = 1. locals: arr = 2 (ref null $str_bytes), i = 3 (i32).
		w.write(2); // 2 local groups
		w.write(1); // group 1: 1 local (arr)
		w.writeRefType(true, WasmLispCompiler.TYPE_STR_BYTES);
		w.write(1); // group 2: 1 local (i)
		w.write(Type.I32);
		int off = 0, len = 1, arr = 2, i = 3;
		// arr = array.new_default $str_bytes (len)
		get(w, len);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		set(w, arr);
		// i = 0
		i32(w, 0);
		set(w, i);
		// while (i < len) { arr[i] = load8_u[off + i]; i++ }
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, i);
		get(w, len);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		// arr[i] = mem[off + i] (array.set pops array, index, value)
		get(w, arr);
		get(w, i);
		get(w, off);
		get(w, i);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		// i++
		get(w, i);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// return struct.new TYPE_STRING (off, len, arr, ci = 0, cb = 1)
		get(w, off);
		get(w, len);
		get(w, arr);
		emitSeedCursor(w);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	/**
	 * Builds {@code _str_fresh} (FUNC_STR_FRESH): identical to {@code _str_build} except
	 * the returned {@code TYPE_STRING}'s {@code id} (field 0) is a fresh value from the
	 * monotonic {@code STRING_ID_CTR} counter instead of the source offset. Every runtime
	 * string build calls this; because the assembly scratch offset is reused across
	 * builds (the heap is a stack now), the offset can no longer serve as a unique
	 * identity, so a counter does -- keeping distinct runtime strings and uninterned
	 * symbols {@code eq}-distinct while their bytes are reclaimed from linear memory.
	 * @return the function body (signature {@code (i32,i32)->(ref null eq)},
	 * TYPE_RAT_NEW)
	 */
	static byte[] buildStrFreshBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: off = 0, len = 1. locals: arr = 2 ($str_bytes), i = 3, id = 4 (i32).
		w.write(2);
		w.write(1);
		w.writeRefType(true, WasmLispCompiler.TYPE_STR_BYTES);
		w.write(2);
		w.write(Type.I32);
		int off = 0, len = 1, arr = 2, i = 3, id = 4;
		// arr = array.new_default $str_bytes (len)
		get(w, len);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		set(w, arr);
		// i = 0; while (i < len) { arr[i] = load8_u[off + i]; i++ }
		i32(w, 0);
		set(w, i);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, i);
		get(w, len);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		get(w, arr);
		get(w, i);
		get(w, off);
		get(w, i);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		get(w, i);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// id = mem[STRING_ID_CTR]; mem[STRING_ID_CTR] = id + 1
		i32(w, WasmLispCompiler.STRING_ID_CTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		set(w, id);
		i32(w, WasmLispCompiler.STRING_ID_CTR_ADDR);
		get(w, id);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// return struct.new TYPE_STRING (id, len, arr, ci = 0, cb = 1)
		get(w, id);
		get(w, len);
		get(w, arr);
		emitSeedCursor(w);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	/**
	 * Builds {@code _str_to_mem} (FUNC_STR_TO_MEM): copies a string's {@code $str_bytes}
	 * array verbatim (surrounding quotes included) into {@code linear[ptr..)} and returns
	 * the byte count. The array->linear bridge for consumers that still require a linear
	 * pointer (WASI iovecs, the reader input scratch, the fetch wire, the host
	 * {@code :string} boundary, runtime intern); content-only callers use
	 * {@code [ptr+1, ptr+len-1)}.
	 * @return the function body (signature {@code ((ref null eq),i32)->i32},
	 * TYPE_STR_TO_MEM)
	 */
	static byte[] buildStrToMemBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: str = 0 (ref null eq), ptr = 1. locals: arr = 2 ($str_bytes), len = 3,
		// i = 4 (i32).
		w.write(2);
		w.write(1);
		w.writeRefType(true, WasmLispCompiler.TYPE_STR_BYTES);
		w.write(2);
		w.write(Type.I32);
		int str = 0, ptr = 1, arr = 2, len = 3, i = 4;
		// arr = str.data; len = array.len(arr)
		get(w, str);
		WasmEmitHelper.emitStrBytesArray(w);
		set(w, arr);
		get(w, arr);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		set(w, len);
		// Ensure [ptr, ptr+len) is within linear memory before writing.
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			get(w, ptr);
			get(w, len);
			w.write(Instruction.I32_ADD);
		});
		// i = 0; while (i < len) { mem[ptr + i] = array.get_u(arr, i); i++ }
		i32(w, 0);
		set(w, i);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, i);
		get(w, len);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		get(w, ptr);
		get(w, i);
		w.write(Instruction.I32_ADD);
		arrGetLocal(w, arr, i);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, i);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// return len
		get(w, len);
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	/**
	 * Builds {@code _write_str_gc} (FUNC_WRITE_STR_GC): writes bytes {@code [from, to)}
	 * of a string value straight from its {@code $str_bytes} array. In capture mode it
	 * appends them directly at {@code CAPTURE_CUR} (no linear staging, so it can never
	 * alias the capture buffer -- the reason the print path reads the array rather than
	 * going through {@code _str_to_mem}); otherwise it stages the slice into heap scratch
	 * at {@code HEAP_PTR} (not advanced -- pure scratch) and hands it to
	 * {@code _write_str} for stdout.
	 *
	 * <p>
	 * {@code esc} selects the two printer modes. With {@code esc = 0} the slice goes out
	 * verbatim: {@code princ} passes {@code (1, len-1)} to strip a string's frame quotes,
	 * and a SYMBOL passes {@code (start, len)} (it has no frame). With {@code esc = 1}
	 * the range is the string's CONTENT ({@code (1, len-1)}) and this function writes the
	 * readable form around it -- an opening {@code "}, the content with every {@code "}
	 * and {@code \} preceded by a {@code \} (the {@code *print-escape*} rule of CLHS
	 * 22.1.3.4, mirroring {@code LispString.escape} on the interpreter and the reader's
	 * un-escaping in {@code WasmReadRuntimeBuilder}), then a closing {@code "}. Framing
	 * inside rather than at the call site is what keeps the two frame quotes from being
	 * escaped themselves.
	 * @return the function body (signature {@code ((ref null eq),i32,i32,i32)->()},
	 * TYPE_WRITE_STR_GC)
	 */
	static byte[] buildWriteStrGcBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: str = 0, from = 1, to = 2, esc = 3. locals: arr = 4 ($str_bytes),
		// dst = 5, i = 6, n = 7, b = 8 (i32). `n` is the worst-case byte count while
		// growing the scratch, then the running OUTPUT count.
		w.write(2);
		w.write(1);
		w.writeRefType(true, WasmLispCompiler.TYPE_STR_BYTES);
		w.write(4);
		w.write(Type.I32);
		int str = 0, from = 1, to = 2, esc = 3, arr = 4, dst = 5, i = 6, n = 7, b = 8;
		// arr = str.data
		get(w, str);
		WasmEmitHelper.emitStrBytesArray(w);
		set(w, arr);
		// n = to - from; if (esc) n = n * 2 + 2 -- every content byte may take a
		// backslash, plus the two frame quotes.
		get(w, to);
		get(w, from);
		w.write(Instruction.I32_SUB);
		set(w, n);
		get(w, esc);
		w.write(Instruction.IF, 0x40);
		get(w, n);
		i32(w, 2);
		w.write(Instruction.I32_MUL);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		set(w, n);
		w.write(Instruction.END);
		// dst = (CAPTURE_FLAG ? CAPTURE_CUR : HEAP_PTR)
		i32(w, WasmLispCompiler.CAPTURE_FLAG_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.IF, 0x40);
		i32(w, WasmLispCompiler.CAPTURE_CUR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		set(w, dst);
		w.write(Instruction.ELSE);
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		set(w, dst);
		w.write(Instruction.END);
		// Ensure [dst, dst+n) is within linear memory before the copy.
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			get(w, dst);
			get(w, n);
			w.write(Instruction.I32_ADD);
		});
		// n = 0 (the output cursor from here on); if (esc) mem[dst + n++] = '"'
		i32(w, 0);
		set(w, n);
		get(w, esc);
		w.write(Instruction.IF, 0x40);
		emitStoreByteAt(w, dst, n, QUOTE);
		emitBump(w, n);
		w.write(Instruction.END);
		// i = from;
		// while (i < to) {
		// b = arr[i];
		// if (esc && (b == '"' || b == '\\')) mem[dst + n++] = '\\';
		// mem[dst + n++] = b; i++;
		// }
		get(w, from);
		set(w, i);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, i);
		get(w, to);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		arrGetLocal(w, arr, i);
		set(w, b);
		get(w, esc);
		w.write(Instruction.IF, 0x40);
		get(w, b);
		i32(w, QUOTE);
		w.write(Instruction.I32_EQ);
		get(w, b);
		i32(w, BACKSLASH);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, 0x40);
		emitStoreByteAt(w, dst, n, BACKSLASH);
		emitBump(w, n);
		w.write(Instruction.END);
		w.write(Instruction.END);
		get(w, dst);
		get(w, n);
		w.write(Instruction.I32_ADD);
		get(w, b);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		emitBump(w, n);
		get(w, i);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// if (esc) mem[dst + n++] = '"'
		get(w, esc);
		w.write(Instruction.IF, 0x40);
		emitStoreByteAt(w, dst, n, QUOTE);
		emitBump(w, n);
		w.write(Instruction.END);
		// Capture mode: CAPTURE_CUR = dst + n, then return. Stdout: _write_str(dst, n).
		i32(w, WasmLispCompiler.CAPTURE_FLAG_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.IF, 0x40);
		i32(w, WasmLispCompiler.CAPTURE_CUR_ADDR);
		get(w, dst);
		get(w, n);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.ELSE);
		get(w, dst);
		get(w, n);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END);
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	// mem[base + offset] = literal byte.
	private static void emitStoreByteAt(WasmWriter w, int baseLocal, int offsetLocal, int value) {
		get(w, baseLocal);
		get(w, offsetLocal);
		w.write(Instruction.I32_ADD);
		i32(w, value);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
	}

	// local++
	private static void emitBump(WasmWriter w, int local) {
		get(w, local);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, local);
	}

	/**
	 * Builds {@code _charvec_p} (FUNC_CHARVEC_P): 1 when the value is a mutable character
	 * vector, else 0. The SHAPE half of {@link #buildCharvecToStrBody()}, and the one
	 * owner of the marker invariant -- a mutable character vector is the general array
	 * representation ({@code TYPE_CELL} box, header {@code (dims . (meta . data))} with
	 * {@code dims}/{@code data} both {@code TYPE_HASH_BUCKETS} arrays) whose meta offset
	 * ({@code meta.cdr.cdr}) is the i31 {@code 1}. That marker is unambiguous, because an
	 * ordinary array's offset is 0 and a displaced array (which can carry a real offset)
	 * holds a {@code TYPE_CELL} target in its data slot, not a buckets array.
	 *
	 * <p>
	 * Eight {@code ref.test}s down to the marker compare, and no element walk, which is
	 * the point: a PREDICATE over a character vector ({@code stringp}) used to answer by
	 * calling {@code _charvec_to_str} and testing whether a string came back, so it
	 * rendered the whole vector -- allocation and all -- to keep one bit, on every call.
	 * @return the function body (signature {@code ((ref null eq))->i32}, TYPE_RAT_GET)
	 */
	static byte[] buildCharvecPBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: v = 0. locals: header = 1, meta = 2 (ref null eq).
		w.write(1);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());
		int v = 0, header = 1, meta = 2;
		// Result block: the 0/1 answer.
		w.write(Instruction.BLOCK);
		w.write(Type.I32);
		// v is TYPE_CELL?
		get(w, v);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		emitFalseUnless(w);
		// header = cell.field0; a cons? (a plain value box holds anything else)
		get(w, v);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		w.writeUnsignedLeb128(0);
		set(w, header);
		get(w, header);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		emitFalseUnless(w);
		// header.car is the dims array? (a hash table's car is an i31 count; anything
		// else is not an array)
		emitConsField(w, header, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		emitFalseUnless(w);
		// header.cdr is a cons?
		emitConsField(w, header, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		emitFalseUnless(w);
		// header.cdr.cdr is the data slot. A STRING there is a string VIEW -- the
		// make-array :displaced-to shape over a string -- and so is a view whose target
		// (a TYPE_CELL) is itself one, which is the same question one hop along.
		emitDataSlot(w, header);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		i32(w, 1);
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);
		emitDataSlot(w, header);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.IF, 0x40);
		emitDataSlot(w, header);
		WasmEmitHelper.emitCharvecPCall(w);
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);
		// otherwise the data slot must be the element array
		emitDataSlot(w, header);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		emitFalseUnless(w);
		// meta = header.cdr.car; a cons whose cdr is a cons?
		emitConsField(w, header, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
		set(w, meta);
		get(w, meta);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		emitFalseUnless(w);
		emitConsField(w, meta, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		emitFalseUnless(w);
		// the marker: meta.cdr.cdr is an i31, and it is 1.
		emitMetaOffset(w, meta);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		emitFalseUnless(w);
		emitMetaOffset(w, meta);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		i32(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.END); // result block
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	// Pushes the data slot (header.cdr.cdr) of the array header in headerSlot: the
	// element buckets, the target CELL of an array view, or the STRING a string view
	// aliases.
	private static void emitDataSlot(WasmWriter w, int headerSlot) {
		emitConsField(w, headerSlot, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
	}

	// Consumes the i32 test on top of the stack: when it is 0, leaves 0 as the enclosing
	// block's i32 result and branches out of it. Only valid one level inside that block.
	private static void emitFalseUnless(WasmWriter w) {
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);
	}

	/**
	 * Builds {@code _charvec_to_str} (FUNC_CHARVEC_TO_STR): normalizes a mutable
	 * character vector into the equivalent quote-framed runtime string; any other value
	 * is returned unchanged. Whether the value IS one is {@code _charvec_p}'s answer
	 * ({@link #buildCharvecPBody()}) -- this function only renders, so the marker
	 * invariant is spelled once and a caller that needs the bit rather than the string
	 * pays no walk. The rendered length is the fill pointer when present, else
	 * {@code dims[0]}; each element is {@code ref.cast TYPE_CHAR} and its code point is
	 * emitted as its 1-4 byte UTF-8 encoding so a non-ASCII / non-BMP character is not
	 * silently truncated to one byte. The bytes are assembled in transient scratch --
	 * {@code CAPTURE_CUR} in capture mode (so a character vector printing inside a
	 * {@code *-to-string} capture cannot clobber the capture buffer, whose base is
	 * {@code HEAP_PTR}), else {@code HEAP_PTR} -- and finalized via {@code _str_fresh}
	 * without advancing either pointer. The scratch is grown to the worst case
	 * ({@code n * 4 + 2}, one 4-byte UTF-8 sequence per character plus the two
	 * surrounding quotes); the actual byte length is passed to {@code _str_fresh}.
	 * @return the function body (signature {@code ((ref null eq))->(ref null eq)},
	 * TYPE_CALLABLE_BASE + 0)
	 */
	static byte[] buildCharvecToStrBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: v = 0. locals: header = 1, meta = 2, data = 3 (ref null eq);
		// n = 4, start = 5, i = 6, cur = 7, code = 8 (i32).
		w.write(2);
		w.write(3);
		w.writeRefType(true, Type.EQ.code());
		w.write(5);
		w.write(Type.I32);
		int v = 0, header = 1, meta = 2, data = 3, n = 4, start = 5, i = 6, cur = 7, code = 8;
		// Result block: the normalized string, or v unchanged.
		w.write(Instruction.BLOCK);
		w.writeRefType(true, Type.EQ.code());
		// if (!_charvec_p(v)) return v
		get(w, v);
		WasmEmitHelper.emitCharvecPCall(w);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		get(w, v);
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);
		// header = cell.field0, data = header.cdr.cdr, meta = header.cdr.car: every cast
		// below is a fact _charvec_p just established.
		get(w, v);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CELL);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		w.writeUnsignedLeb128(0);
		set(w, header);
		emitConsField(w, header, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
		set(w, data);
		emitConsField(w, header, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
		set(w, meta);
		// n = fill pointer (meta.car) when present, else dims[0]
		emitConsField(w, meta, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF);
		w.write(Type.I32);
		emitConsField(w, meta, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.ELSE);
		emitConsField(w, header, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		i32(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.END);
		set(w, n);
		// start = CAPTURE_FLAG ? CAPTURE_CUR : HEAP_PTR -- the transient assembly
		// scratch. In capture mode the capture buffer's base IS HEAP_PTR, so the
		// scratch sits at the capture cursor instead (still above every captured
		// byte); _str_fresh copies the assembled bytes out before anything appends
		// there, and neither pointer is advanced.
		i32(w, WasmLispCompiler.CAPTURE_FLAG_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.IF, 0x40);
		i32(w, WasmLispCompiler.CAPTURE_CUR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		set(w, start);
		w.write(Instruction.ELSE);
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		set(w, start);
		w.write(Instruction.END);
		// Ensure the whole output [start, start + n*4 + 2) fits before writing
		// (worst case: every character encodes to 4 UTF-8 bytes).
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			get(w, start);
			get(w, n);
			i32(w, 4);
			w.write(Instruction.I32_MUL);
			w.write(Instruction.I32_ADD);
			i32(w, 2);
			w.write(Instruction.I32_ADD);
		});
		// mem[start] = '"'
		get(w, start);
		i32(w, QUOTE);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// cur = start + 1 (write cursor for content bytes)
		get(w, start);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, cur);
		// for i in 0..n: encode data[i].code as UTF-8 at mem[cur..], advance cur.
		i32(w, 0);
		set(w, i);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, i);
		get(w, n);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		// code = data[i].code (the i32 code point stored in the TYPE_CHAR at slot i).
		// A string VIEW owns no data slot, so its element comes from the shared
		// displacement walk instead -- one ref.test per character, against the whole
		// UTF-8 encode below.
		get(w, data);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.IF);
		w.write(Type.I32);
		get(w, data);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		get(w, i);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.ELSE);
		get(w, header);
		get(w, i);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_ARR_GET);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END);
		set(w, code);
		emitUtf8Encode(w, code, cur);
		get(w, i);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// mem[cur] = '"'; total byte length = (cur + 1) - start.
		get(w, cur);
		i32(w, QUOTE);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// _str_fresh(start, cur + 1 - start): a runtime string with a fresh counter id.
		get(w, start);
		get(w, cur);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		get(w, start);
		w.write(Instruction.I32_SUB);
		WasmEmitHelper.emitStrFreshCall(w);
		w.write(Instruction.END); // result block
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	// Encodes the Unicode code point in codeLocal as 1-4 UTF-8 bytes at linear memory
	// starting at curLocal, and advances curLocal by the number of bytes emitted. The
	// caller has already grown the linear heap to the worst-case capacity.
	private static void emitUtf8Encode(WasmWriter w, int codeLocal, int curLocal) {
		// if (code < 0x80) { mem[cur] = code; cur += 1 }
		get(w, codeLocal);
		i32(w, 0x80);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x40);
		get(w, curLocal);
		get(w, codeLocal);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, curLocal);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, curLocal);
		w.write(Instruction.ELSE);
		// else if (code < 0x800) { 2-byte sequence }
		get(w, codeLocal);
		i32(w, 0x800);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x40);
		// mem[cur] = 0xC0 | (code >> 6)
		get(w, curLocal);
		get(w, codeLocal);
		i32(w, 6);
		w.write(Instruction.I32_SHR_U);
		i32(w, 0xC0);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// mem[cur+1] = 0x80 | (code & 0x3F)
		get(w, curLocal);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		get(w, codeLocal);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 0x80);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, curLocal);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		set(w, curLocal);
		w.write(Instruction.ELSE);
		// else if (code < 0x10000) { 3-byte sequence }
		get(w, codeLocal);
		i32(w, 0x10000);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x40);
		// mem[cur] = 0xE0 | (code >> 12)
		get(w, curLocal);
		get(w, codeLocal);
		i32(w, 12);
		w.write(Instruction.I32_SHR_U);
		i32(w, 0xE0);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// mem[cur+1] = 0x80 | ((code >> 6) & 0x3F)
		get(w, curLocal);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		get(w, codeLocal);
		i32(w, 6);
		w.write(Instruction.I32_SHR_U);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 0x80);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// mem[cur+2] = 0x80 | (code & 0x3F)
		get(w, curLocal);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		get(w, codeLocal);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 0x80);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, curLocal);
		i32(w, 3);
		w.write(Instruction.I32_ADD);
		set(w, curLocal);
		w.write(Instruction.ELSE);
		// else { 4-byte sequence }
		// mem[cur] = 0xF0 | (code >> 18)
		get(w, curLocal);
		get(w, codeLocal);
		i32(w, 18);
		w.write(Instruction.I32_SHR_U);
		i32(w, 0xF0);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// mem[cur+1] = 0x80 | ((code >> 12) & 0x3F)
		get(w, curLocal);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		get(w, codeLocal);
		i32(w, 12);
		w.write(Instruction.I32_SHR_U);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 0x80);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// mem[cur+2] = 0x80 | ((code >> 6) & 0x3F)
		get(w, curLocal);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		get(w, codeLocal);
		i32(w, 6);
		w.write(Instruction.I32_SHR_U);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 0x80);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// mem[cur+3] = 0x80 | (code & 0x3F)
		get(w, curLocal);
		i32(w, 3);
		w.write(Instruction.I32_ADD);
		get(w, codeLocal);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 0x80);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, curLocal);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		set(w, curLocal);
		w.write(Instruction.END); // closes IF (code < 0x10000)
		w.write(Instruction.END); // closes IF (code < 0x800)
		w.write(Instruction.END); // closes IF (code < 0x80)
	}

	/**
	 * Builds {@code _str_char_count} (FUNC_STR_CHAR_COUNT): returns the number of Unicode
	 * characters in a UTF-8 encoded {@code TYPE_STRING}. Resumes the character-index
	 * cursor (fields 3/4, see {@link #buildStrCharByteOffsetBody()}) rather than counting
	 * from the first byte: a cursor sitting on the closing quote ({@code cb == len - 1})
	 * already holds the count, so a repeated {@code (length s)} is one compare. Otherwise
	 * the walk resumes at {@code cb} and leaves the cursor on the terminator, which both
	 * answers every later {@code length} in constant time and -- for a single-byte
	 * string, where the cursor then reads {@code cb == ci + 1} -- makes every later
	 * character index constant time as well. Every {@code (length s)} for a string reads
	 * through this, so a source-literal string and a char-vec-derived string both report
	 * their true character count.
	 * @return the function body (signature {@code ((ref null eq)) -> i32}, TYPE_RAT_GET)
	 */
	static byte[] buildStrCharCountBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: str = 0. locals: s = 1 (TYPE_STRING), arr = 2 ($str_bytes); len = 3,
		// pos = 4, count = 5, b = 6, step = 7 (i32).
		w.write(3);
		w.write(1);
		w.writeRefType(true, WasmLispCompiler.TYPE_STRING);
		w.write(1);
		w.writeRefType(true, WasmLispCompiler.TYPE_STR_BYTES);
		w.write(5);
		w.write(Type.I32);
		int str = 0, s = 1, arr = 2, len = 3, pos = 4, count = 5, b = 6, step = 7;
		// s = (TYPE_STRING) str; arr = s.data; len = array.len(arr)
		castStringLocal(w, str, s);
		setStrDataAndLen(w, s, arr, len);
		// If len < 2 the value is not a real string; return 0 for safety.
		get(w, len);
		i32(w, 2);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// The cursor is on the closing quote: everything before it has been counted.
		getCursor(w, s, CURSOR_BYTE);
		set(w, pos);
		get(w, pos);
		get(w, len);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		getCursor(w, s, CURSOR_CHAR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// count = cursor character index; resume the walk at its byte offset.
		getCursor(w, s, CURSOR_CHAR);
		set(w, count);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, pos);
		get(w, len);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		arrGetLocal(w, arr, pos);
		set(w, b);
		emitUtf8Width(w, b, step);
		get(w, pos);
		get(w, step);
		w.write(Instruction.I32_ADD);
		set(w, pos);
		get(w, count);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, count);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// Park the cursor on the terminator: (count, len - 1) is a valid pair -- those
		// count characters do occupy bytes [1, len - 1) -- and it only ever moves the
		// cursor forward.
		get(w, len);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, pos);
		setCursorFrom(w, s, CURSOR_CHAR, count);
		setCursorFrom(w, s, CURSOR_BYTE, pos);
		get(w, count);
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	/**
	 * Builds {@code _str_char_byte_offset} (FUNC_STR_CHAR_BYTE_OFFSET): given a UTF-8
	 * encoded string {@code str} and a character index {@code i}, returns the byte offset
	 * within {@code str}'s {@code $str_bytes} array where the {@code i}-th character's
	 * first byte lives. Character indexing starts at 0. If {@code i} is at or past the
	 * character count, returns {@code len - 1} (the position of the closing quote) so
	 * callers scanning to a computed end-of-range land on the string terminator.
	 * {@code subseq} calls this twice (start and end) to translate a character range to a
	 * byte range.
	 *
	 * <p>
	 * The walk resumes from the string's own CURSOR (fields 3/4: character {@code ci}
	 * starts at byte {@code cb}, seeded {@code (0, 1)} by the builders) instead of
	 * restarting at the first byte, which is what keeps a left-to-right scan linear
	 * instead of quadratic. Two facts do all the work:
	 *
	 * <ul>
	 * <li>{@code cb == ci + 1} says the {@code ci} characters before the cursor are one
	 * byte each, so ANY index at or below {@code ci} is {@code 1 + i} -- the whole answer
	 * for a single-byte string, at any index, in any order.
	 * <li>otherwise the walk starts at whichever of the cursor and the string start is
	 * nearer, forwards or backwards, and the cursor follows the index it answered.
	 * </ul>
	 *
	 * <p>
	 * The cursor is only ever stored when the walk landed exactly on character {@code i}
	 * ({@code remaining == 0}); an index past the end answers the terminator without
	 * claiming a character lives there. Reaching the general path at all implies
	 * {@code i > ci || cb != ci + 1}, so the store can never cost the single-byte-prefix
	 * fact the fast path above reads. A string's bytes never change after it is built (an
	 * indexed write rebuilds, {@code .kb/string-write-runtime.md}), so a cursor cannot go
	 * stale.
	 * @return the function body (signature {@code ((ref null eq), i32) -> i32},
	 * TYPE_STR_TO_MEM)
	 */
	static byte[] buildStrCharByteOffsetBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: str = 0, i = 1. locals: s = 2 (TYPE_STRING), arr = 3 ($str_bytes);
		// len = 4, pos = 5, remaining = 6, b = 7, step = 8, ci = 9, cb = 10 (i32).
		w.write(3);
		w.write(1);
		w.writeRefType(true, WasmLispCompiler.TYPE_STRING);
		w.write(1);
		w.writeRefType(true, WasmLispCompiler.TYPE_STR_BYTES);
		w.write(7);
		w.write(Type.I32);
		int str = 0, iParam = 1, s = 2, arr = 3, len = 4, pos = 5, remaining = 6, b = 7, step = 8, ci = 9, cb = 10;
		// A negative index answers character 0's offset, which is what the plain walk
		// answered (its remaining <= 0 exit fired before the first step). Clamping here
		// rather than letting one flow through is not politeness: a negative index would
		// otherwise walk BACKWARDS past the opening quote and store a cursor pointing
		// outside the array, and every later index on that string would trap.
		i32(w, 0);
		get(w, iParam);
		get(w, iParam);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.SELECT);
		set(w, iParam);
		castStringLocal(w, str, s);
		setStrDataAndLen(w, s, arr, len);
		getCursor(w, s, CURSOR_CHAR);
		set(w, ci);
		getCursor(w, s, CURSOR_BYTE);
		set(w, cb);
		// Single-byte prefix (cb == ci + 1) and i within it: the offset is 1 + i.
		get(w, cb);
		get(w, ci);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_EQ);
		get(w, iParam);
		get(w, ci);
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		i32(w, 1);
		get(w, iParam);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// Anchor at the cursor when it is at or before i, or nearer to i than the start
		// is; otherwise start from the first character.
		get(w, iParam);
		get(w, ci);
		w.write(Instruction.I32_GE_S);
		get(w, ci);
		get(w, iParam);
		w.write(Instruction.I32_SUB);
		get(w, iParam);
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, 0x40);
		get(w, cb);
		set(w, pos);
		get(w, iParam);
		get(w, ci);
		w.write(Instruction.I32_SUB);
		set(w, remaining);
		w.write(Instruction.ELSE);
		i32(w, 1);
		set(w, pos);
		get(w, iParam);
		set(w, remaining);
		w.write(Instruction.END);
		// Forward: step over one UTF-8 sequence per remaining character, stopping at the
		// closing quote so an index past the end lands on the terminator.
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, remaining);
		i32(w, 0);
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.BR_IF, 1);
		get(w, pos);
		get(w, len);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		arrGetLocal(w, arr, pos);
		set(w, b);
		emitUtf8Width(w, b, step);
		get(w, pos);
		get(w, step);
		w.write(Instruction.I32_ADD);
		set(w, pos);
		get(w, remaining);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, remaining);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// Backward: one character back is one byte back plus every continuation byte
		// (0b10xxxxxx) below it. Only reached when the anchor was the cursor and i is
		// before it, so it can never step below the opening quote.
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, remaining);
		i32(w, 0);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		get(w, pos);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, pos);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, pos);
		i32(w, 1);
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.BR_IF, 1);
		arrGetLocal(w, arr, pos);
		i32(w, 0xC0);
		w.write(Instruction.I32_AND);
		i32(w, 0x80);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 1);
		get(w, pos);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, pos);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop (continuation bytes)
		w.write(Instruction.END); // block
		get(w, remaining);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, remaining);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop (characters)
		w.write(Instruction.END); // block
		// Record where character i lives, unless the walk ran off the end first.
		get(w, remaining);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		setCursorFrom(w, s, CURSOR_CHAR, iParam);
		setCursorFrom(w, s, CURSOR_BYTE, pos);
		w.write(Instruction.END);
		get(w, pos);
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	// Given a UTF-8 lead byte in bLocal, sets stepLocal to its sequence width (1-4).
	// A byte with high two bits 10 (a stray continuation) is treated as width 1 so the
	// walk still advances.
	private static void emitUtf8Width(WasmWriter w, int bLocal, int stepLocal) {
		get(w, bLocal);
		i32(w, 0x80);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 1);
		w.write(Instruction.ELSE);
		get(w, bLocal);
		i32(w, 0xE0);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 2);
		w.write(Instruction.ELSE);
		get(w, bLocal);
		i32(w, 0xF0);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 3);
		w.write(Instruction.ELSE);
		i32(w, 4);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
		set(w, stepLocal);
	}

	/**
	 * Builds {@code _str_char_at} (FUNC_STR_CHAR_AT): given a UTF-8 encoded string
	 * {@code str} and a character index {@code i}, returns the code point of the
	 * {@code i}-th character. Delegates the walk to {@code _str_char_byte_offset}, then
	 * decodes the 1-4 byte UTF-8 sequence starting at the returned byte position. Every
	 * {@code (char s i)} / {@code (schar s i)} lowering reads through this; the caller
	 * boxes the returned i32 as a {@code TYPE_CHAR} struct. A request past the end
	 * returns 0 (matches the reader's out-of-bounds ASCII byte fetch it replaces).
	 * @return the function body (signature {@code ((ref null eq), i32) -> i32},
	 * TYPE_STR_TO_MEM)
	 */
	static byte[] buildStrCharAtBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: str = 0, i = 1. locals: arr = 2 ($str_bytes);
		// len = 3, pos = 4, b0 = 5, b1 = 6, b2 = 7, b3 = 8 (i32).
		w.write(2);
		w.write(1);
		w.writeRefType(true, WasmLispCompiler.TYPE_STR_BYTES);
		w.write(6);
		w.write(Type.I32);
		int str = 0, iParam = 1, arr = 2, len = 3, pos = 4, b0 = 5, b1 = 6, b2 = 7, b3 = 8;
		// pos = _str_char_byte_offset(str, i)
		get(w, str);
		get(w, iParam);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_STR_CHAR_BYTE_OFFSET);
		set(w, pos);
		setStrArray(w, str, arr);
		get(w, arr);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		set(w, len);
		// If pos >= len - 1, return 0 (out of bounds).
		get(w, pos);
		get(w, len);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// b0 = arr[pos]
		arrGetLocal(w, arr, pos);
		set(w, b0);
		// if (b0 < 0x80) return b0
		get(w, b0);
		i32(w, 0x80);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x40);
		get(w, b0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// else if (b0 < 0xE0) return ((b0 & 0x1F) << 6) | (b1 & 0x3F)
		get(w, b0);
		i32(w, 0xE0);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x40);
		get(w, arr);
		get(w, pos);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		set(w, b1);
		get(w, b0);
		i32(w, 0x1F);
		w.write(Instruction.I32_AND);
		i32(w, 6);
		w.write(Instruction.I32_SHL);
		get(w, b1);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// else if (b0 < 0xF0) 3-byte sequence
		get(w, b0);
		i32(w, 0xF0);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x40);
		get(w, arr);
		get(w, pos);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		set(w, b1);
		get(w, arr);
		get(w, pos);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		set(w, b2);
		get(w, b0);
		i32(w, 0x0F);
		w.write(Instruction.I32_AND);
		i32(w, 12);
		w.write(Instruction.I32_SHL);
		get(w, b1);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 6);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		get(w, b2);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// else 4-byte sequence
		get(w, arr);
		get(w, pos);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		set(w, b1);
		get(w, arr);
		get(w, pos);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		set(w, b2);
		get(w, arr);
		get(w, pos);
		i32(w, 3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		set(w, b3);
		get(w, b0);
		i32(w, 0x07);
		w.write(Instruction.I32_AND);
		i32(w, 18);
		w.write(Instruction.I32_SHL);
		get(w, b1);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 12);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		get(w, b2);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 6);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		get(w, b3);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	// Pushes the meta offset (meta.cdr.cdr) of the meta cons held in the given local.
	private static void emitMetaOffset(WasmWriter w, int metaLocal) {
		emitConsField(w, metaLocal, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
	}

	// Pushes field (0 = car, 1 = cdr) of the TYPE_CONS held in the given local.
	private static void emitConsField(WasmWriter w, int local, int field) {
		get(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(field);
	}

	/**
	 * Builds {@code _string_upcase} (when {@code upcase} is true) or
	 * {@code _string_downcase} (param 0 = string).
	 * @param upcase whether to upcase (otherwise downcase)
	 * @return the function body
	 */
	static byte[] buildCaseConvertBody(boolean upcase) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// Locals 1..7: pos, end, start, cur, cp, step, k (i32); 8: inArr ($str_bytes).
		// cp doubles as the scratch the designator scan needs before the loop starts.
		declareI32AndStrArrayLocals(w, 7, 1);
		int pos = 1, end = 2, start = 3, cur = 4, cp = 5, step = 6, k = 7, inArr = 8;
		setStrArray(w, 0, inArr);
		emitStringContentRange(w, 0, pos, end);
		emitCaseFoldCore(w, inArr, pos, end, start, cur, cp, step, k, -1, upcase ? UPCASE : DOWNCASE);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds {@code _string_capitalize} (param 0 = string).
	 * @return the function body
	 */
	static byte[] buildCapitalizeBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// Locals 1..8: pos, end, start, cur, cp, step, k, atWordStart (i32); 9: inArr
		// ($str_bytes).
		declareI32AndStrArrayLocals(w, 8, 1);
		int pos = 1, end = 2, start = 3, cur = 4, cp = 5, step = 6, k = 7, ws = 8, inArr = 9;
		setStrArray(w, 0, inArr);
		emitStringContentRange(w, 0, pos, end);
		emitCaseFoldCore(w, inArr, pos, end, start, cur, cp, step, k, ws, CAPITALIZE);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds {@code _subseq} (param 0 = sequence, param 1 = start, param 2 = end or nil).
	 * The sequence is either a string struct or a cons chain (nil = null); the runtime
	 * type is tested with {@code ref.test} to select the branch. For a string the content
	 * range is copied into a fresh quoted heap string; for a list the elements from
	 * {@code start} up to {@code end} are copied into a fresh cons chain. A nil
	 * {@code end} defaults to the sequence length.
	 * @return the function body
	 */
	static byte[] buildSubseqBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// ref locals 3..6: node, head, tail, newc.
		// i32 locals 7..14: pos, end, start, cur, b, startIdx, endIdx, ii.
		// ref local 15: strArr (the input string's $str_bytes, for the string branch).
		w.write(3);
		w.write(4);
		w.writeRefType(true, Type.EQ.code());
		w.write(8);
		w.write(Type.I32);
		w.write(1);
		w.writeRefType(true, WasmLispCompiler.TYPE_STR_BYTES);
		int node = 3, head = 4, tail = 5, newc = 6;
		int pos = 7, end = 8, start = 9, cur = 10, b = 11, startIdx = 12, endIdx = 13, ii = 14;
		int strArr = 15;
		// startIdx = i31(startArg); endIdx = (endArg nil) ? -1 : i31(endArg)
		emitI31GetS(w, 1);
		set(w, startIdx);
		get(w, 2);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, -1);
		w.write(Instruction.ELSE);
		emitI31GetS(w, 2);
		w.write(Instruction.END);
		set(w, endIdx);
		// Dispatch: string struct vs list.
		get(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		// --- String branch ---
		// strArr = string.data; pos/end are 0-based byte positions in that array.
		// The character indices in startIdx/endIdx are translated to byte offsets by
		// the UTF-8 walking helper _str_char_byte_offset so a subseq over a string
		// carrying non-ASCII characters preserves them.
		setStrArray(w, 0, strArr);
		// pos = _str_char_byte_offset(str, startIdx)
		get(w, 0);
		get(w, startIdx);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_STR_CHAR_BYTE_OFFSET);
		set(w, pos);
		// end = (endIdx < 0) ? length - 1 : _str_char_byte_offset(str, endIdx)
		get(w, endIdx);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF);
		w.write(Type.I32);
		emitStrLen(w, 0);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.ELSE);
		get(w, 0);
		get(w, endIdx);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_STR_CHAR_BYTE_OFFSET);
		w.write(Instruction.END);
		set(w, end);
		emitBuildCore(w, strArr, pos, end, start, cur, b);
		w.write(Instruction.ELSE);
		// --- List branch ---
		emitSubseqList(w, node, head, tail, newc, startIdx, endIdx, ii);
		w.write(Instruction.END); // dispatch if
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	// Copies the elements of the list in param 0 from index startIdxLocal up to
	// endIdxLocal (or to the end when endIdxLocal < 0) into a fresh cons chain, left on
	// the stack.
	private static void emitSubseqList(WasmWriter w, int node, int head, int tail, int newc, int startIdx, int endIdx,
			int ii) {
		// node = seq
		get(w, 0);
		set(w, node);
		// Skip the first startIdx cells: ii = 0; while (ii < startIdx && node is cons)
		i32(w, 0);
		set(w, ii);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, ii);
		get(w, startIdx);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		get(w, node);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		emitCdr(w, node);
		set(w, node);
		get(w, ii);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, ii);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// head = null; tail = null; ii = startIdx
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		set(w, head);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		set(w, tail);
		get(w, startIdx);
		set(w, ii);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		// stop when node is not a cons
		get(w, node);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		// stop when endIdx >= 0 && ii >= endIdx
		get(w, endIdx);
		i32(w, 0);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		get(w, ii);
		get(w, endIdx);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 2);
		w.write(Instruction.END);
		// newc = cons(car(node), nil)
		get(w, node);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		set(w, newc);
		// if (head is null) head = tail = newc; else tail.cdr = newc; tail = newc
		get(w, head);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		get(w, newc);
		set(w, head);
		get(w, newc);
		set(w, tail);
		w.write(Instruction.ELSE);
		get(w, tail);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		get(w, newc);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
		get(w, newc);
		set(w, tail);
		w.write(Instruction.END);
		// node = cdr(node); ii++
		emitCdr(w, node);
		set(w, node);
		get(w, ii);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, ii);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// result = head
		get(w, head);
	}

	// Pushes cdr (field 1) of the cons held in the given local.
	private static void emitCdr(WasmWriter w, int local) {
		get(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
	}

	/**
	 * Builds {@code _string_eq} ({@code ignoreCase} false) or {@code _string_equal}
	 * ({@code ignoreCase} true). Params 0 and 1 are strings. Returns the symbol {@code t}
	 * on equality, otherwise nil.
	 * @param ignoreCase whether the comparison is case-insensitive (ASCII)
	 * @param st the string table (to intern the {@code t} symbol)
	 * @return the function body
	 */
	static byte[] buildStringEqBody(boolean ignoreCase, WasmLispCompiler.StringTable st) {
		WasmLispCompiler.StringTable.StringEntry t = st.addBodyString("T");
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// Locals 2..5: i, n, ca, cb (i32); 6..7: aArr, bArr ($str_bytes).
		declareI32AndStrArrayLocals(w, 4, 2);
		int i = 2, n = 3, ca = 4, cb = 5, aArr = 6, bArr = 7;
		// Result block: a string struct (t) or null (nil).
		w.write(Instruction.BLOCK);
		w.writeRefType(true, Type.EQ.code());
		// if (a.length != b.length) return nil
		emitStrLen(w, 0);
		emitStrLen(w, 1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);
		// aArr = a.data; bArr = b.data; n = a.length; i = 0
		setStrArray(w, 0, aArr);
		setStrArray(w, 1, bArr);
		emitStrLen(w, 0);
		set(w, n);
		i32(w, 0);
		set(w, i);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, i);
		get(w, n);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		// ca = aArr[i]; cb = bArr[i]
		arrGetLocal(w, aArr, i);
		set(w, ca);
		arrGetLocal(w, bArr, i);
		set(w, cb);
		// if (norm(ca) != norm(cb)) return nil
		emitMaybeLower(w, ca, ignoreCase);
		emitMaybeLower(w, cb, ignoreCase);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.BR, 3);
		w.write(Instruction.END);
		get(w, i);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// all bytes equal -> t
		i32(w, t.offset());
		i32(w, t.length());
		WasmEmitHelper.emitStrBuildCall(w);
		w.write(Instruction.END); // result block
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	/**
	 * Builds {@code _string_trim} (param 0 = char bag, param 1 = string, param 2 = mode:
	 * 0 = both ends, 1 = left only, 2 = right only).
	 * @return the function body
	 */
	static byte[] buildTrimBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// Locals 3..13: bagStart, bagEnd, lo, hi, mode, c, found, scan, start, cur, b
		// (i32);
		// 14..15: bagArr, strArr ($str_bytes).
		declareI32AndStrArrayLocals(w, 11, 2);
		int bagStart = 3, bagEnd = 4, lo = 5, hi = 6, mode = 7, c = 8, found = 9, scan = 10, start = 11, cur = 12,
				b = 13, bagArr = 14, strArr = 15;
		// bagArr = bag.data; strArr = string.data.
		setStrArray(w, 0, bagArr);
		setStrArray(w, 1, strArr);
		// bagStart = 1; bagEnd = bag.length - 1 (indices into bagArr, skipping the
		// quotes)
		i32(w, 1);
		set(w, bagStart);
		emitStrLen(w, 0);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, bagEnd);
		// lo = 1; hi = string.length - 1 (indices into strArr)
		i32(w, 1);
		set(w, lo);
		emitStrLen(w, 1);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, hi);
		// mode = i31(modeArg)
		emitI31GetS(w, 2);
		set(w, mode);
		// Left trim (mode != 2): advance lo while in bag and lo < hi.
		get(w, mode);
		i32(w, 2);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, lo);
		get(w, hi);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		// c = strArr[lo]
		arrGetLocal(w, strArr, lo);
		set(w, c);
		emitInBag(w, c, bagArr, bagStart, bagEnd, found, scan);
		get(w, found);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		get(w, lo);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, lo);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END); // if
		// Right trim (mode != 1): retreat hi while in bag and hi > lo.
		get(w, mode);
		i32(w, 1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, hi);
		get(w, lo);
		w.write(Instruction.I32_LE_U);
		w.write(Instruction.BR_IF, 1);
		// c = strArr[hi - 1]
		get(w, strArr);
		get(w, hi);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		set(w, c);
		emitInBag(w, c, bagArr, bagStart, bagEnd, found, scan);
		get(w, found);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		get(w, hi);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, hi);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END); // if
		emitBuildCore(w, strArr, lo, hi, start, cur, b);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds {@code _iv_utf8_str} (FUNC_IV_UTF8_STR): a packed {@code (unsigned-byte 8)}
	 * vector (a bare {@code TYPE_I8ARR}) validated as STRICT UTF-8 and, when it is,
	 * turned into the {@code TYPE_STRING} its bytes spell by ONE {@code array.copy}
	 * between the two storage quotes; anything else answers nil. The native half of the
	 * prelude's {@code rontolisp::%octets-to-string}, so a well-formed body -- every real
	 * one -- decodes at the speed of a copy and only malformed bytes pay the per-byte
	 * loop.
	 *
	 * <p>
	 * The validator is the strict one, deliberately NOT the walk
	 * {@link #buildStrCharAtBody()} decodes with: that one only needs a byte COUNT per
	 * lead byte and accepts ranges UTF-8 does not spell (an overlong encoding, a
	 * surrogate, a code point past U+10FFFF, a bare continuation byte). Copying under
	 * those looser ranges is what makes the raw copy wrong on malformed input -- the
	 * lenient rule turns such a byte into its own character, and only a strict validator
	 * can say whether the copy would agree. So: {@code 0xC2..0xDF} leads one
	 * continuation, {@code 0xE0..0xEF} two ({@code 0xE0} needs {@code A0..BF} first, so
	 * an overlong 3-byte form is refused; {@code 0xED} needs {@code 80..9F}, so a
	 * surrogate is), {@code 0xF0..0xF4} three ({@code 0xF0} needs {@code 90..BF};
	 * {@code 0xF4} needs {@code 80..8F}, the U+10FFFF ceiling), every continuation is
	 * {@code 10xxxxxx}, and a truncated tail is refused.
	 * @return the function body (signature {@code ((ref null eq)) -> (ref null eq)},
	 * TYPE_CALLABLE_BASE + 0)
	 */
	static byte[] buildIvUtf8StrBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// param: v = 0. locals: arr = 1 (TYPE_I8ARR), out = 2 ($str_bytes);
		// n = 3, i = 4, b = 5, c = 6, k = 7, lo = 8, hi = 9, id = 10 (i32).
		w.write(3);
		w.write(1);
		w.writeRefType(true, WasmLispCompiler.TYPE_I8ARR);
		w.write(1);
		w.writeRefType(true, WasmLispCompiler.TYPE_STR_BYTES);
		w.write(8);
		w.write(Type.I32);
		int v = 0, arr = 1, out = 2, n = 3, i = 4, b = 5, c = 6, k = 7, lo = 8, hi = 9, id = 10;

		// Not a packed octet vector -> nil. The caller's loop walks the value through the
		// generic aref, so a character vector or a general array is its business.
		get(w, v);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		get(w, v);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
		set(w, arr);
		get(w, arr);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		set(w, n);
		i32(w, 0);
		set(w, i);
		// The two landing pads: br 2 out of the loop is "valid", br 1 is "not UTF-8".
		w.write(Instruction.BLOCK, 0x40); // A: valid
		w.write(Instruction.BLOCK, 0x40); // B: invalid
		w.write(Instruction.LOOP, 0x40);
		get(w, i);
		get(w, n);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 2);
		get(w, arr);
		get(w, i);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_I8ARR);
		set(w, b);
		// k = continuation-byte count; lo/hi = the range the FIRST one must be in.
		i32(w, 0);
		set(w, k);
		i32(w, 0x80);
		set(w, lo);
		i32(w, 0xBF);
		set(w, hi);
		get(w, b);
		i32(w, 0x80);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.IF, 0x40);
		// 80..C1 leads nothing (a stray continuation byte, or an overlong 2-byte form)
		// and F5.. is past U+10FFFF.
		get(w, b);
		i32(w, 0xC2);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.BR_IF, 2);
		get(w, b);
		i32(w, 0xF5);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 2);
		get(w, b);
		i32(w, 0xE0);
		w.write(Instruction.I32_GE_U);
		get(w, b);
		i32(w, 0xF0);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, k);
		emitFirstContinuationBound(w, b, 0xE0, lo, 0xA0);
		emitFirstContinuationBound(w, b, 0xED, hi, 0x9F);
		emitFirstContinuationBound(w, b, 0xF0, lo, 0x90);
		emitFirstContinuationBound(w, b, 0xF4, hi, 0x8F);
		w.write(Instruction.END);
		// A sequence the vector truncates is not valid UTF-8 either.
		get(w, i);
		get(w, k);
		w.write(Instruction.I32_ADD);
		get(w, n);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		// The first continuation carries the range that rules out an overlong form, a
		// surrogate and the past-U+10FFFF tail; the rest only have to be 10xxxxxx.
		get(w, k);
		w.write(Instruction.IF, 0x40);
		emitContinuationByte(w, arr, i, 1, c);
		get(w, c);
		get(w, lo);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.BR_IF, 2);
		get(w, c);
		get(w, hi);
		w.write(Instruction.I32_GT_U);
		w.write(Instruction.BR_IF, 2);
		w.write(Instruction.END);
		emitTrailingContinuation(w, arr, i, k, c, 2);
		emitTrailingContinuation(w, arr, i, k, c, 3);
		get(w, i);
		get(w, k);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // B: invalid
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // A: valid
		// out = array.new_default $str_bytes (n + 2); the frame quotes, then ONE copy of
		// the content -- the whole point of the strict path.
		get(w, n);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		set(w, out);
		get(w, out);
		i32(w, 0);
		i32(w, QUOTE);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		get(w, out);
		get(w, n);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		i32(w, QUOTE);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		get(w, out);
		i32(w, 1);
		get(w, arr);
		i32(w, 0);
		get(w, n);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_COPY);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_I8ARR);
		// id = STRING_ID_CTR++ -- a decoded body is a runtime string, so it gets a fresh
		// counter id (what _str_fresh would have stamped, without its linear detour).
		i32(w, WasmLispCompiler.STRING_ID_CTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		set(w, id);
		i32(w, WasmLispCompiler.STRING_ID_CTR_ADDR);
		get(w, id);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		get(w, id);
		get(w, n);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		get(w, out);
		emitSeedCursor(w);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	// "if the lead byte is exactly `lead`, the first continuation's bound `boundLocal`
	// becomes `value`" -- the four narrowings that turn a byte-count walk into a
	// validator (overlong 3- and 4-byte forms, surrogates, the U+10FFFF ceiling).
	private static void emitFirstContinuationBound(WasmWriter w, int bLocal, int lead, int boundLocal, int value) {
		get(w, bLocal);
		i32(w, lead);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		i32(w, value);
		set(w, boundLocal);
		w.write(Instruction.END);
	}

	// "when the sequence is at least `offset` + 1 bytes long, byte i + offset must be a
	// 10xxxxxx continuation" -- branching to the invalid landing pad when it is not.
	// Emitted at the loop-body level, so the pad is two blocks out from inside the if.
	private static void emitTrailingContinuation(WasmWriter w, int arrLocal, int iLocal, int kLocal, int cLocal,
			int offset) {
		get(w, kLocal);
		i32(w, offset);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.IF, 0x40);
		emitContinuationByte(w, arrLocal, iLocal, offset, cLocal);
		get(w, cLocal);
		i32(w, 0xC0);
		w.write(Instruction.I32_AND);
		i32(w, 0x80);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 2);
		w.write(Instruction.END);
	}

	// cLocal = arr[i + offset].
	private static void emitContinuationByte(WasmWriter w, int arrLocal, int iLocal, int offset, int cLocal) {
		get(w, arrLocal);
		get(w, iLocal);
		i32(w, offset);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_I8ARR);
		set(w, cLocal);
	}

	// --- Shared emit helpers ---

	private static void get(WasmWriter w, int local) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(local);
	}

	private static void set(WasmWriter w, int local) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(local);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	// TYPE_STRING's character-index cursor: character CURSOR_CHAR of the string starts at
	// byte CURSOR_BYTE of its $str_bytes array. Both are (mut i32) and both are seeded
	// (0, 1) -- character 0 always starts right after the opening quote -- so the pair is
	// valid from the moment a string is built. See buildStrCharByteOffsetBody().
	private static final int CURSOR_CHAR = 3;

	private static final int CURSOR_BYTE = 4;

	// Pushes the seed cursor operands a struct.new TYPE_STRING needs after its data
	// array.
	private static void emitSeedCursor(WasmWriter w) {
		i32(w, 0);
		i32(w, 1);
	}

	// Pushes cursor field `field` of the TYPE_STRING held in sLocal.
	private static void getCursor(WasmWriter w, int sLocal, int field) {
		get(w, sLocal);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeUnsignedLeb128(field);
	}

	// Stores valueLocal into cursor field `field` of the TYPE_STRING held in sLocal.
	private static void setCursorFrom(WasmWriter w, int sLocal, int field, int valueLocal) {
		get(w, sLocal);
		get(w, valueLocal);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeUnsignedLeb128(field);
	}

	// sLocal = (TYPE_STRING) paramLocal -- the concrete type, so the cursor fields are
	// reachable and the data array read below needs no second cast of the parameter.
	private static void castStringLocal(WasmWriter w, int paramLocal, int sLocal) {
		get(w, paramLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		set(w, sLocal);
	}

	// arrLocal = the $str_bytes data array (field 2) of the TYPE_STRING in sLocal;
	// lenLocal = its byte length.
	private static void setStrDataAndLen(WasmWriter w, int sLocal, int arrLocal, int lenLocal) {
		get(w, sLocal);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STR_BYTES);
		set(w, arrLocal);
		get(w, arrLocal);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		set(w, lenLocal);
	}

	// Sets arrLocal to the $str_bytes data array (field 2) of the string at the given
	// param local, so byte reads become array.get_u indices (old linear[id+i] == arr[i]).
	private static void setStrArray(WasmWriter w, int paramLocal, int arrLocal) {
		get(w, paramLocal);
		WasmEmitHelper.emitStrBytesArray(w);
		set(w, arrLocal);
	}

	// Pushes the unsigned byte arr[idx] where arr is the $str_bytes in arrLocal and idx
	// is
	// the value in idxLocal.
	private static void arrGetLocal(WasmWriter w, int arrLocal, int idxLocal) {
		get(w, arrLocal);
		get(w, idxLocal);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
	}

	// Declares n i32 locals followed by refCount (ref null $str_bytes) locals.
	private static void declareI32AndStrArrayLocals(WasmWriter w, int n, int refCount) {
		w.write(2);
		w.write(n);
		w.write(Type.I32);
		w.write(refCount);
		w.writeRefType(true, WasmLispCompiler.TYPE_STR_BYTES);
	}

	// Pushes the length field (1) of the string at the given param local.
	private static void emitStrLen(WasmWriter w, int paramLocal) {
		get(w, paramLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeUnsignedLeb128(1);
	}

	// Unboxes the i31 integer at the given param local to an i32 on the stack.
	private static void emitI31GetS(WasmWriter w, int paramLocal) {
		get(w, paramLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
	}

	// Sets posLocal/endLocal to the CONTENT byte range of a quoted runtime string (the
	// range between its surrounding quotes, [1, len-1)), as 0-based indices into its
	// $str_bytes. The case functions used to do a string-designator scan of their own
	// here -- a leading quote meant a string, anything else was a symbol name taken from
	// after its LAST colon. That is now the shared (string ...) coercion's job
	// (LispMacroExpander.normalizeStringDesignatorArg), applied by the dispatcher to
	// every designator position instead of to this one family, so a CHARACTER designator
	// works too and a non-designator still signals. The build core re-wraps the copied
	// content in quotes, yielding a proper string.
	private static void emitStringContentRange(WasmWriter w, int paramLocal, int posLocal, int endLocal) {
		// pos = 1; end = len - 1 (strip the surrounding quotes).
		i32(w, 1);
		set(w, posLocal);
		emitStrLen(w, paramLocal);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, endLocal);
	}

	// Copies bytes [posL, endL) of the input array inArrL (a $str_bytes, indexed 0-based)
	// into a fresh heap string (wrapped in quotes) and leaves the new string struct on
	// the stack. A plain byte copy: subseq and the trim family slice at character
	// boundaries the caller already resolved, so no UTF-8 decoding is needed here (the
	// case operators, which DO transform per code point, use emitCaseFoldCore). The
	// output is assembled in a reused linear scratch at HEAP_PTR (NOT advanced -- a stack
	// pop) and finalized via _str_fresh (a runtime string gets a counter id); the INPUT
	// read goes through the GC array.
	private static void emitBuildCore(WasmWriter w, int inArrL, int posL, int endL, int startL, int curL, int bL) {
		// start = HEAP_PTR; mem[start] = '"'; cur = start + 1
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		set(w, startL);
		// Ensure the whole output [start, start + (end-pos) + 2) fits before writing.
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			get(w, startL);
			get(w, endL);
			get(w, posL);
			w.write(Instruction.I32_SUB);
			w.write(Instruction.I32_ADD);
			i32(w, 2);
			w.write(Instruction.I32_ADD);
		});
		get(w, startL);
		i32(w, QUOTE);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, startL);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, curL);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, posL);
		get(w, endL);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		arrGetLocal(w, inArrL, posL);
		set(w, bL);
		get(w, curL);
		get(w, bL);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, curL);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, curL);
		get(w, posL);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, posL);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// mem[cur] = '"'. HEAP_PTR is NOT advanced: _str_fresh copies the assembled bytes
		// into a fresh GC array, after which the scratch is dead, so leaving HEAP_PTR at
		// `start` (a stack pop) reuses it for the next build -- this is what retires the
		// linear string-heap leak.
		get(w, curL);
		i32(w, QUOTE);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// _str_fresh(start, cur + 1 - start): a runtime string gets a fresh counter id.
		get(w, startL);
		get(w, curL);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		get(w, startL);
		w.write(Instruction.I32_SUB);
		WasmEmitHelper.emitStrFreshCall(w);
	}

	// Case-folds bytes [posL, endL) of the input array inArrL into a fresh heap string
	// (wrapped in quotes) and leaves the new string struct on the stack. Unlike
	// emitBuildCore this walks CODE POINTS, not bytes: each 1-4 byte UTF-8 sequence is
	// decoded, the code point is folded through the shared _char_upcase / _char_downcase
	// range-table helpers -- the same ones (char-upcase ch) calls -- and re-encoded. That
	// is what CLHS asks for ("apply char-upcase to each character"), and it is what keeps
	// the WASM backends byte-identical to the interpreter and the JVM compile path
	// instead of folding ASCII only.
	private static void emitCaseFoldCore(WasmWriter w, int inArrL, int posL, int endL, int startL, int curL, int cpL,
			int stepL, int kL, int wsL, int mode) {
		// start = HEAP_PTR; mem[start] = '"'; cur = start + 1
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		set(w, startL);
		// A fold grows a code point's UTF-8 encoding by at most maxUtf8Growth() bytes
		// (1 today: U+0250 upcases to U+2C6F, two bytes in and three out) and a code
		// point occupies at least one input byte, so (1 + growth) * inputBytes bounds
		// the content. The bound is DERIVED from the baked tables rather than assumed,
		// so a wider future Unicode baseline widens it too.
		final int growthFactor = 1 + WasmCaseFoldRuntimeBuilder.maxUtf8Growth();
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			get(w, startL);
			get(w, endL);
			get(w, posL);
			w.write(Instruction.I32_SUB);
			i32(w, growthFactor);
			w.write(Instruction.I32_MUL);
			w.write(Instruction.I32_ADD);
			i32(w, 2);
			w.write(Instruction.I32_ADD);
		});
		get(w, startL);
		i32(w, QUOTE);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, startL);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, curL);
		if (mode == CAPITALIZE) {
			i32(w, 1);
			set(w, wsL);
		}
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, posL);
		get(w, endL);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		emitUtf8DecodeAt(w, inArrL, posL, cpL, stepL, kL);
		get(w, posL);
		get(w, stepL);
		w.write(Instruction.I32_ADD);
		set(w, posL);
		emitFoldCodePoint(w, cpL, wsL, mode);
		emitUtf8Encode(w, cpL, curL);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// mem[cur] = '"'. HEAP_PTR is NOT advanced, exactly as in emitBuildCore: the
		// scratch is dead once _str_fresh has copied it into a fresh GC array.
		get(w, curL);
		i32(w, QUOTE);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, startL);
		get(w, curL);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		get(w, startL);
		w.write(Instruction.I32_SUB);
		WasmEmitHelper.emitStrFreshCall(w);
	}

	// Decodes the UTF-8 sequence starting at inArrL[posL] into cpL and sets stepL to its
	// byte width. kL is a scratch cursor. A stray continuation byte decodes as itself
	// with width 1, matching emitUtf8Width's forgiving walk.
	private static void emitUtf8DecodeAt(WasmWriter w, int inArrL, int posL, int cpL, int stepL, int kL) {
		arrGetLocal(w, inArrL, posL);
		set(w, cpL);
		emitUtf8Width(w, cpL, stepL);
		get(w, stepL);
		i32(w, 1);
		w.write(Instruction.I32_GT_U);
		w.write(Instruction.IF, 0x40);
		// cp = lead & (0x7F >> step): 0x1F for a 2-byte lead, 0x0F for 3, 0x07 for 4.
		get(w, cpL);
		i32(w, 0x7F);
		get(w, stepL);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.I32_AND);
		set(w, cpL);
		// for k in 1..step-1: cp = (cp << 6) | (arr[pos + k] & 0x3F)
		i32(w, 1);
		set(w, kL);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, kL);
		get(w, stepL);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		get(w, cpL);
		i32(w, 6);
		w.write(Instruction.I32_SHL);
		get(w, inArrL);
		get(w, posL);
		get(w, kL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		set(w, cpL);
		get(w, kL);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, kL);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// Applies the case transform to the code point held in cpL (in place).
	private static void emitFoldCodePoint(WasmWriter w, int cpL, int wsL, int mode) {
		switch (mode) {
			case UPCASE -> emitCaseFoldCall(w, cpL, WasmLispCompiler.FUNC_CHAR_UPCASE);
			case DOWNCASE -> emitCaseFoldCall(w, cpL, WasmLispCompiler.FUNC_CHAR_DOWNCASE);
			case CAPITALIZE -> {
				// A word constituent is any full-Unicode letter or digit, so a caseless
				// letter continues the word instead of starting a new one.
				get(w, cpL);
				w.write(Instruction.CALL);
				w.writeUnsignedLeb128(WasmLispCompiler.FUNC_CHAR_ALNUM_P);
				w.write(Instruction.IF, 0x40);
				get(w, wsL);
				w.write(Instruction.IF, 0x40);
				emitCaseFoldCall(w, cpL, WasmLispCompiler.FUNC_CHAR_UPCASE);
				w.write(Instruction.ELSE);
				emitCaseFoldCall(w, cpL, WasmLispCompiler.FUNC_CHAR_DOWNCASE);
				w.write(Instruction.END);
				i32(w, 0);
				set(w, wsL);
				w.write(Instruction.ELSE);
				i32(w, 1);
				set(w, wsL);
				w.write(Instruction.END);
			}
			default -> throw new IllegalArgumentException("Unknown fold mode: " + mode);
		}
	}

	private static void emitCaseFoldCall(WasmWriter w, int cpL, int funcIndex) {
		get(w, cpL);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(funcIndex);
		set(w, cpL);
	}

	// Pushes 1 if the byte in bL is in [lo, hi] (unsigned), else 0.
	private static void emitInRange(WasmWriter w, int bL, int lo, int hi) {
		get(w, bL);
		i32(w, lo);
		w.write(Instruction.I32_GE_U);
		get(w, bL);
		i32(w, hi);
		w.write(Instruction.I32_LE_U);
		w.write(Instruction.I32_AND);
	}

	// Pushes the byte in the given local, ASCII-lowercased when ignoreCase is set.
	private static void emitMaybeLower(WasmWriter w, int local, boolean ignoreCase) {
		if (!ignoreCase) {
			get(w, local);
			return;
		}
		// b + ((b >= 'A' && b <= 'Z') ? 32 : 0)
		get(w, local);
		emitInRange(w, local, 65, 90);
		i32(w, 32);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
	}

	// Sets foundLocal to 1 if the byte in cLocal occurs in the bag array bagArrLocal over
	// the index range [bagStartLocal, bagEndLocal), else 0. scanLocal is a scratch
	// cursor.
	private static void emitInBag(WasmWriter w, int cLocal, int bagArrLocal, int bagStartLocal, int bagEndLocal,
			int foundLocal, int scanLocal) {
		i32(w, 0);
		set(w, foundLocal);
		get(w, bagStartLocal);
		set(w, scanLocal);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, scanLocal);
		get(w, bagEndLocal);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		arrGetLocal(w, bagArrLocal, scanLocal);
		get(w, cLocal);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		i32(w, 1);
		set(w, foundLocal);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);
		get(w, scanLocal);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, scanLocal);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

}
