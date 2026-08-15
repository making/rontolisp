package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the runtime helper functions that support the memory-backed
 * ({@code :string}/{@code :s-expr}) {@code rontolisp:wasm-export} marshalling: a
 * host-facing bump allocator and a "linear-memory bytes to Lisp string" constructor.
 * Emitted only when a program declares at least one memory-typed export.
 *
 * <p>
 * Under {@code --component} three more helper shapes are appended so a
 * {@code :string}/{@code :s-expr} export can cross the canonical string ABI:
 * {@code cabi_realloc} (the host lowers string arguments through it; it also snapshots
 * the heap pointer + runtime intern count into the {@code CABI_MARK_*} cells on the first
 * call of an export invocation), one {@code cabi_post_*} post-return per flat-result
 * signature (restores the heap pointer to the snapshot once the host has copied the
 * results out -- skipped when interning happened during the call, whose permanent heap
 * copies must survive), and one return-pointer shim per
 * {@code :string}/{@code :s-expr}-returning export (the canonical ABI caps flat results
 * at one, so the shim spills the wrapper's two {@code (ptr,len)} values into an 8-byte
 * record and returns its address).
 */
final class WasmExportRuntimeBuilder {

	private WasmExportRuntimeBuilder() {
	}

	/**
	 * Builds {@code cabi_realloc(old i32, old-size i32, align i32, new-size i32) -> i32}:
	 * the canonical-ABI reallocation entry point the host calls to lower string arguments
	 * into this module's memory (each string is lowered with {@code old = 0} and an exact
	 * size, so there is never a live block to preserve). On the first call of an export
	 * invocation (mark not active) it snapshots the heap pointer and the runtime intern
	 * count so the matching {@code cabi_post_*} can pop the per-call allocations, then
	 * delegates to {@code __ronto_alloc} (grow-guarded; its 8-byte alignment satisfies
	 * every canonical alignment).
	 * @param allocFuncIndex the function index of {@code __ronto_alloc}
	 * @return the function body bytes (signature {@code (i32,i32,i32,i32) -> i32})
	 */
	static byte[] buildCabiReallocBody(int allocFuncIndex) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // no locals
		// if (!MARK_ACTIVE) { MARK_ACTIVE = 1; MARK_HEAP = HEAP_PTR;
		// MARK_INTERN = RT_INTERN_COUNT }
		w.write(Instruction.BLOCK);
		w.write(0x40);
		loadCell(w, WasmLispCompiler.CABI_MARK_ACTIVE_ADDR);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CABI_MARK_ACTIVE_ADDR);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CABI_MARK_HEAP_ADDR);
		loadCell(w, WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CABI_MARK_INTERN_ADDR);
		loadCell(w, WasmLispCompiler.RT_INTERN_COUNT_ADDR);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.END);
		// return __ronto_alloc(new-size)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(allocFuncIndex);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds a {@code cabi_post_*(flat results) -> ()} post-return body: the host calls
	 * it after copying the results out of memory. Restores the heap pointer to the
	 * {@code cabi_realloc} snapshot -- freeing the host-lowered argument strings and the
	 * shim's return-area record -- UNLESS interning happened during the call
	 * (intern-count guard, like the serve adapter's per-request reset): {@code _intern}
	 * copies first-seen tokens into permanent heap storage, and popping those would
	 * dangle the intern records, so such a call simply keeps its allocations. The flat
	 * result parameters are ignored, so one body shape serves every signature.
	 * @return the function body bytes
	 */
	static byte[] buildCabiPostReturnBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // no locals
		w.write(Instruction.BLOCK);
		w.write(0x40);
		// no mark (no realloc happened) -> nothing to pop
		loadCell(w, WasmLispCompiler.CABI_MARK_ACTIVE_ADDR);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(0);
		// interning happened since the snapshot -> keep this call's allocations
		loadCell(w, WasmLispCompiler.RT_INTERN_COUNT_ADDR);
		loadCell(w, WasmLispCompiler.CABI_MARK_INTERN_ADDR);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(0);
		// HEAP_PTR = MARK_HEAP
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		loadCell(w, WasmLispCompiler.CABI_MARK_HEAP_ADDR);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.END);
		// MARK_ACTIVE = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.CABI_MARK_ACTIVE_ADDR);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the return-pointer shim for a {@code :string}/{@code :s-expr}-returning
	 * export: the canonical ABI caps flat results at one, so the lifted core function
	 * must return a single i32 pointing at an 8-byte {@code (ptr,len)} record instead of
	 * the wrapper's two values. The record is allocated through {@code cabi_realloc}
	 * BEFORE the wrapper call -- the wrapper stages its result bytes at the un-advanced
	 * {@code HEAP_PTR} scratch, so allocating after would overwrite them -- and freed by
	 * the post-return above. Locals: ptr, len, ret (i32 each, after the host parameter
	 * slots).
	 * @param wrapperFuncIndex the function index of the export's (untouched) two-value
	 * wrapper
	 * @param cabiReallocFuncIndex the function index of {@code cabi_realloc}
	 * @param paramSlots the wrapper's WASM parameter slot count
	 * @return the function body bytes (signature: the wrapper's params {@code -> i32})
	 */
	static byte[] buildRetptrShimBody(int wrapperFuncIndex, int cabiReallocFuncIndex, int paramSlots) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		int ptr = paramSlots;
		int len = paramSlots + 1;
		int ret = paramSlots + 2;
		w.write(1);
		w.write(3);
		w.write(Type.I32);
		// ret = cabi_realloc(0, 0, 4, 8) -- also snapshots the mark when the host lowered
		// no string argument first
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(4);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(8);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(cabiReallocFuncIndex);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(ret);
		// (ptr, len) = wrapper(params...)
		for (int s = 0; s < paramSlots; s++) {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(s);
		}
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(wrapperFuncIndex);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(len);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(ptr);
		// record[0] = ptr; record[4] = len; return record
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(ret);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(ptr);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(ret);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(len);
		w.write(Instruction.I32_STORE, 0x02, 0x04);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(ret);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// Pushes mem[addr] (a fixed i32 cell).
	private static void loadCell(WasmWriter w, int addr) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(addr);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
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
		w.writeUnsignedLeb128(1);
		// memory[HEAP_PTR_ADDR] = (old + size + 7) & ~7
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(7);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(-8);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// Ensure [old, old+size) is within linear memory before the host writes into it.
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(1);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.I32_ADD);
		});
		// return old
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds {@code __ronto_alloc_mark() -> i32}: the host arena API, the wasm-GC
	 * counterpart of the {@code --no-gc} pair. Returns the current bump-heap top
	 * ({@code HEAP_PTR_ADDR}); a resident host snapshots it BEFORE allocating its input
	 * buffer with {@code __ronto_alloc} and pops back to it with
	 * {@code __ronto_alloc_reset} after the call, so a per-call input buffer no longer
	 * grows linear memory. (Everything else the call allocates is a GC object the engine
	 * reclaims -- this API exists for the one thing the engine cannot see, the linear
	 * memory at the host boundary.)
	 * @return the function body bytes (signature {@code () -> i32})
	 */
	static byte[] buildAllocMarkBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // no locals
		loadCell(w, WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds {@code __ronto_alloc_reset(mark i32) -> ()}: the host arena API. Restores
	 * the bump-heap top to a value previously returned by {@code __ronto_alloc_mark} --
	 * but never below the interned-symbol byte pool's high-water mark
	 * ({@code RT_INTERN_HEAP_ADDR}), because on this backend {@code HEAP_PTR} is a stack
	 * pointer over a permanent low region: {@code _intern} copies first-seen tokens into
	 * it and the intern registry keeps pointing at those bytes forever. So the pop is
	 * {@code HEAP_PTR = max(mark, intern-high-water)} -- always safe, needs no active
	 * flag (hence it nests), and reclaims everything above the permanent region. A call
	 * that interned a symbol above the mark therefore keeps the host's buffer alive (the
	 * permanent bytes sit on top of it); that is the price of a stable symbol identity,
	 * and interning inside a reactor export is rare.
	 * @param clampParkFloor whether ({@code --reentrant}) the pop must ALSO stay above
	 * the park-block region's floor ({@code PARK_FLOOR_ADDR}): a park block carved while
	 * this bracket was open holds another in-flight call's staging, and popping below it
	 * would hand its bytes out again
	 * @return the function body bytes (signature {@code (i32) -> ()})
	 */
	static byte[] buildAllocResetBody(boolean clampParkFloor) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int mark = 0;
		final int water = 1;
		w.write(1);
		w.write(1);
		w.write(Type.I32);
		loadCell(w, WasmLispCompiler.RT_INTERN_HEAP_ADDR);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(water);
		if (clampParkFloor) {
			// water = max(water, park-floor): both are permanent floors.
			loadCell(w, WasmLispCompiler.PARK_FLOOR_ADDR);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(water);
			w.write(Instruction.I32_GT_U);
			w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			loadCell(w, WasmLispCompiler.PARK_FLOOR_ADDR);
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(water);
			w.write(Instruction.END);
		}
		// HEAP_PTR = mark > water ? mark : water
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(mark);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(water);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(mark);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(water);
		w.write(Instruction.I32_GT_U);
		w.write(Instruction.SELECT);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
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
		w.writeUnsignedLeb128(dst);
		// Ensure [dst, dst+len+2) is within linear memory before writing the quoted copy.
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(dst);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(len);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(2);
			w.write(Instruction.I32_ADD);
		});
		// memory[dst] = '"'
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(dst);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// idx = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(idx);
		// while (idx < len) memory[dst+1+idx] = memory[ptr+idx]; idx++
		w.write(Instruction.BLOCK);
		w.write(0x40); // void block type
		w.write(Instruction.LOOP);
		w.write(0x40);
		// if idx >= len, break out of block (depth 1)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(len);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		// addr = dst + 1 + idx
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(dst);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.I32_ADD);
		// value = memory[ptr + idx]
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(ptr);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// idx++
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(idx);
		// continue loop (depth 0)
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// memory[dst+1+len] = '"'
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(dst);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(len);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// HEAP_PTR is NOT advanced (a stack pop): _str_fresh copies the quoted bytes into
		// a
		// fresh GC array with a counter id, so the scratch region is reused. (The host's
		// source buffer at ptr is __ronto_alloc'd and owned by the host, untouched.)
		// return _str_fresh(dst, len + 2)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(dst);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(len);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		WasmEmitHelper.emitStrFreshCall(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds {@code _bytes_from_mem(ptr i32, len i32) -> (ref null eq)}: copies
	 * {@code len} raw bytes from linear memory into a fresh {@code (unsigned-byte 8)}
	 * vector (a bare {@code TYPE_I8ARR} array) -- <strong>no UTF-8 decode</strong>, which
	 * is the point of the {@code :bytes} boundary type: the string decoder is
	 * non-validating and corrupts arbitrary binary. Used to box a {@code :bytes} export
	 * argument a host wrote into memory, and to lift a component byte-stream read's chunk
	 * (an HTTP body, a socket's recv) as the octets it is.
	 * @return the function body bytes (signature {@code (i32,i32) -> (ref null eq)},
	 * reuses TYPE_RAT_NEW)
	 */
	static byte[] buildBytesFromMemBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int ptr = 0;
		final int len = 1;
		final int arr = 2;
		final int idx = 3;
		// locals: arr (ref null TYPE_I8ARR), idx (i32)
		w.write(2);
		w.write(1);
		w.writeRefType(true, WasmLispCompiler.TYPE_I8ARR);
		w.write(1);
		w.write(Type.I32);
		// arr = array.new_default TYPE_I8ARR (len)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(len);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_I8ARR);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(arr);
		// idx = 0; while (idx < len) arr[idx] = mem[ptr+idx]; idx++
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(len);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(arr);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(ptr);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_I8ARR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(arr);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds {@code _bytes_copy(v (ref null eq), ptr i32, cap i32) -> i32}: copies
	 * {@code min(len,cap)} raw bytes of the {@code (unsigned-byte 8)} vector {@code v}
	 * into {@code linear[ptr..)} and returns the vector's FULL length {@code len} -- the
	 * caller-passes-the-buffer {@code read(2)} convention of a {@code :bytes} result: an
	 * undersized buffer is a retry, not a truncation. The {@code ref.cast} traps on a
	 * non-byte-vector value (exact-or-trap). The destination is caller-owned
	 * ({@code __ronto_alloc}'d by the host or staged by an import wrapper), so no grow
	 * guard: an out-of-memory pointer traps on the store.
	 * @return the function body bytes (signature {@code ((ref null eq),i32,i32) -> i32})
	 */
	static byte[] buildBytesCopyBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int v = 0;
		final int ptr = 1;
		final int cap = 2;
		final int arr = 3;
		final int len = 4;
		final int count = 5;
		final int idx = 6;
		// locals: arr (ref null TYPE_I8ARR), len/count/idx (i32)
		w.write(2);
		w.write(1);
		w.writeRefType(true, WasmLispCompiler.TYPE_I8ARR);
		w.write(3);
		w.write(Type.I32);
		// arr = ref.cast v ; len = array.len(arr)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(v);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(arr);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(arr);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(len);
		// count = min(len, cap) (unsigned)
		emitMinU(w, len, cap, count);
		// idx = 0; while (idx < count) mem[ptr+idx] = array.get_u(arr, idx); idx++
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(count);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(ptr);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(arr);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_I8ARR);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// return len (the FULL length)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(len);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds {@code _bytes_fill(v (ref null eq), ptr i32, n i32) -> i32}: copies
	 * {@code min(array.len(v), n)} raw bytes from {@code linear[ptr..)} into the
	 * {@code (unsigned-byte 8)} vector {@code v} and returns {@code n} unchanged -- the
	 * receive half of a {@code :bytes} import result (the host wrote up to the buffer's
	 * capacity and answered the full length {@code n}; bytes past {@code n} in the vector
	 * are left untouched, like {@code read(2)}).
	 * @return the function body bytes (signature {@code ((ref null eq),i32,i32) -> i32})
	 */
	static byte[] buildBytesFillBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int v = 0;
		final int ptr = 1;
		final int n = 2;
		final int arr = 3;
		final int alen = 4;
		final int count = 5;
		final int idx = 6;
		// locals: arr (ref null TYPE_I8ARR), alen/count/idx (i32)
		w.write(2);
		w.write(1);
		w.writeRefType(true, WasmLispCompiler.TYPE_I8ARR);
		w.write(3);
		w.write(Type.I32);
		// arr = ref.cast v ; alen = array.len(arr)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(v);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(arr);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(arr);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(alen);
		// count = min(alen, n) (unsigned)
		emitMinU(w, alen, n, count);
		// idx = 0; while (idx < count) array.set(arr, idx, mem[ptr+idx]); idx++
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(count);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(arr);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(ptr);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_I8ARR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// return n (the host's full-length answer, unchanged)
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(n);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds {@code _park_alloc(n i32) -> i32} ({@code --reentrant} only, exported as
	 * {@code __ronto_park_alloc}): allocates a linear-memory region that stays valid
	 * while a call is PARKED on a suspending import -- unlike the {@code HEAP_PTR}
	 * scratch stack, whose top moves whenever any other in-flight call runs. Blocks are
	 * recycled through a first-fit free list ({@code PARK_FREE_ADDR}); a miss carves a
	 * new block by permanently advancing the bump heap through {@code __ronto_alloc}
	 * (grow-guarded) and raises the park floor ({@code PARK_FLOOR_ADDR}), which every
	 * arena pop in a reentrant module clamps to. Blocks are whole -- never split, never
	 * coalesced -- so a steady overlap of same-sized staging (the pull-loop shape)
	 * recycles perfectly, and a burst of mixed sizes retains at most one block per
	 * concurrent size profile. Layout: an 8-byte {@code [size, next]} header, data
	 * follows; the returned pointer is the data.
	 * @param allocFuncIndex the function index of {@code __ronto_alloc}
	 * @return the function body bytes (signature {@code (i32) -> i32}, reuses
	 * TYPE_LOOKUP)
	 */
	static byte[] buildParkAllocBody(int allocFuncIndex) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int n = 0;
		final int p = 1;
		final int prev = 2;
		w.write(1);
		w.write(2);
		w.write(Type.I32);
		// n = (n + 7) & ~7 -- the __ronto_alloc rounding, so a recycled block's size
		// compares against the same grid it was carved on.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(n);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(7);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(-8);
		w.write(Instruction.I32_AND);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(n);
		// p = PARK_FREE; prev = 0
		loadCell(w, WasmLispCompiler.PARK_FREE_ADDR);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(p);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(prev);
		w.write(Instruction.BLOCK, 0x40); // $found
		w.write(Instruction.BLOCK, 0x40); // $carve
		w.write(Instruction.LOOP, 0x40); // $scan
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(p);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1); // end of list -> $carve
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(p);
		w.write(Instruction.I32_LOAD, 0x02, 0x00); // block size
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(n);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(2); // fits -> $found
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(p);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(prev);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(p);
		w.write(Instruction.I32_LOAD, 0x02, 0x04); // next
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(p);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // $carve
		// p = __ronto_alloc(n + 8); p[0] = n; PARK_FLOOR = HEAP_PTR; return p + 8
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(n);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(allocFuncIndex);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(p);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(p);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(n);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PARK_FLOOR_ADDR);
		loadCell(w, WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(p);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // $found
		// unlink p: (prev ? prev : the head cell).next = p.next
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(prev);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PARK_FREE_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(p);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(prev);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(p);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		w.write(Instruction.I32_STORE, 0x02, 0x04);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(p);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds {@code _park_free(ptr i32) -> ()} ({@code --reentrant} only, exported as
	 * {@code __ronto_park_free}): returns a {@code _park_alloc} block to the free list.
	 * {@code ptr} is the DATA pointer the alloc answered; the header sits 8 bytes below
	 * it.
	 * @return the function body bytes (signature {@code (i32) -> ()})
	 */
	static byte[] buildParkFreeBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int ptr = 0;
		final int blk = 1;
		w.write(1);
		w.write(1);
		w.write(Type.I32);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(ptr);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(8);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(blk);
		// blk.next = PARK_FREE; PARK_FREE = blk
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(blk);
		loadCell(w, WasmLispCompiler.PARK_FREE_ADDR);
		w.write(Instruction.I32_STORE, 0x02, 0x04);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PARK_FREE_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(blk);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds {@code _park_str_result(v (ref null eq)) -> (i32, i32)} ({@code
	 * --reentrant} only): stages a {@code :string}/{@code :s-expr} export RESULT in a
	 * park block instead of at the un-advanced {@code HEAP_PTR} scratch. The scratch is
	 * trampled by whatever wasm runs next, and under overlap something else CAN run
	 * between this export's return and the host's decode (the promising promise settles a
	 * microtask later) -- todo-337's measured second corruption. The host decodes the
	 * {@code (ptr, len)} and then frees it with {@code __ronto_park_free(ptr)}. Content
	 * pointer and length, quotes stripped, so the pointer the host frees is exactly the
	 * one it was answered.
	 * @param parkAllocFuncIndex the function index of {@code _park_alloc}
	 * @return the function body bytes (signature {@code ((ref null eq)) -> (i32,i32)})
	 */
	static byte[] buildParkStrResultBody(int parkAllocFuncIndex) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int v = 0;
		final int base = 1;
		final int n = 2;
		final int p = 3;
		final int idx = 4;
		w.write(1);
		w.write(4);
		w.write(Type.I32);
		// base = HEAP_PTR; n = _str_to_mem(v, base) -- the quoted spelling at scratch.
		loadCell(w, WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(base);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(v);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(base);
		WasmEmitHelper.emitStrToMemCall(w);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(n);
		// HEAP_PTR = base + align8(n): _park_alloc may CARVE from the bump heap, and a
		// carve at the un-advanced scratch would put the block header on top of the
		// bytes just written. Advance over them first.
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(base);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(n);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(7);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(-8);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// p = _park_alloc(n - 2); copy the content bytes (quotes stripped).
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(n);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(parkAllocFuncIndex);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(p);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(n);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(p);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(base);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(idx);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// HEAP_PTR = max(base, PARK_FLOOR): pop the scratch spelling, but never below a
		// block the alloc above may just have carved on top of it.
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(base);
		loadCell(w, WasmLispCompiler.PARK_FLOOR_ADDR);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(base);
		loadCell(w, WasmLispCompiler.PARK_FLOOR_ADDR);
		w.write(Instruction.I32_GT_U);
		w.write(Instruction.SELECT);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// (ptr, len) -- content only.
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(p);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(n);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// local[out] = min(local[a], local[b]), unsigned.
	private static void emitMinU(WasmWriter w, int a, int b, int out) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(a);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(b);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(a);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(b);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.SELECT);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(out);
	}

}
