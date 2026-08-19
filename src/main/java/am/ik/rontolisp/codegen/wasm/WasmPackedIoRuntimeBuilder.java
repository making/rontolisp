package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.WasmWriter;
import am.ik.wasm.Type;

/**
 * Builds the {@code _read_packed} / {@code _write_packed} runtime helper bodies -- the
 * bulk binary transfer behind {@code read-sequence} / {@code write-sequence} over a
 * PACKED buffer on the wasm-GC backends ({@code .kb/binary-sequence-io.md}).
 *
 * <p>
 * {@code _read_packed(seq, stream, start, end) -> value} fills the row-major elements
 * {@code [start, end)} of a packed float array (any rank; a {@code TYPE_F32ARR} /
 * {@code TYPE_F64ARR} data array, or under {@code --simd} the {@code TYPE_VBLOCK} lane
 * groups through {@code _v_set}) or a packed {@code (unsigned-byte 8|16|32)} vector (the
 * bare {@code TYPE_I8ARR} / {@code TYPE_I16ARR} / {@code TYPE_I32ARR}) with raw
 * little-endian elements read off a WASI fd through {@code fd_read} in chunks of up to 64
 * KiB staged at {@code HEAP_PTR} (reserved for the call and popped after it, the
 * {@code _open} discipline), and answers the fill position as an i31. A short read is
 * refilled until the chunk is full or the fd is at EOF; a trailing partial element at EOF
 * is dropped. {@code _write_packed} is the mirror: elements are staged into the same
 * chunk and pushed through {@code fd_write} until every byte is written, and the sequence
 * is answered.
 *
 * <p>
 * Both answer {@code null} -- "declined" -- for a buffer that is not packed or a stream
 * that is not a WASI fd (a negative i31 is a string stream), which sends the expansion
 * down its element loop exactly as before. The standard-stream designator (nil / t) is fd
 * 0 for a read and fd 1 for a write, as {@code _read_byte} / {@code _write_byte} take it;
 * a write to fd 1 keeps the {@code LINE_START} fresh-line flag off its last byte, as
 * {@code _write_byte} does. {@code start} / {@code end} are i31s or nil (0 / the total
 * size); a range outside the buffer traps.
 */
final class WasmPackedIoRuntimeBuilder {

	/** The staging chunk: a multiple of every element width. */
	static final int CHUNK_BYTES = 65536;

	// The buffer shape, an i32 tag: which array type holds the elements and how wide
	// they are on the wire.
	private static final int KIND_F32 = 0, KIND_F64 = 1, KIND_VBLOCK = 2, KIND_I8 = 3, KIND_I16 = 4, KIND_I32 = 5;

	private WasmPackedIoRuntimeBuilder() {
	}

	/**
	 * Builds the {@code _read_packed} body.
	 * @param simd whether the module is a {@code --simd} build (packed float data is a
	 * {@code TYPE_VBLOCK} written through {@code _v_set})
	 * @return the function body bytes
	 */
	static byte[] buildReadPackedBody(boolean simd) {
		return build(true, simd);
	}

	/**
	 * Builds the {@code _write_packed} body.
	 * @param simd whether the module is a {@code --simd} build (packed float data is a
	 * {@code TYPE_VBLOCK} read through {@code _v_get})
	 * @return the function body bytes
	 */
	static byte[] buildWritePackedBody(boolean simd) {
		return build(false, simd);
	}

	private static byte[] build(boolean read, boolean simd) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: SEQ=0, STREAM=1, START=2, END=3 (ref) ; i32 locals: FD=4, KIND=5,
		// WIDTH=6, SIZE=7, S=8, E=9, I=10, BUF=11, SAVED_HP=12, NEED=13, POS=14, GOT=15,
		// N=16, K=17 ; ref local: DATA=18
		w.write(2);
		w.write(14);
		w.write(Type.I32);
		w.write(1);
		w.writeRefType(true, Type.EQ.code());
		final int SEQ = 0, STREAM = 1, START = 2, END = 3, FD = 4, KIND = 5, WIDTH = 6, SIZE = 7, S = 8, E = 9, I = 10,
				BUF = 11, SAVED_HP = 12, NEED = 13, POS = 14, GOT = 15, N = 16, K = 17, DATA = 18;
		final int IOV = WasmLispCompiler.IOV_OFFSET;
		final int NWRITTEN = WasmLispCompiler.NWRITTEN_OFFSET;

		// --- the buffer shape: kind / width / size / data ------------------------------
		w.write(Instruction.BLOCK, 0x40); // $shaped
		// a packed float array?
		getLocal(w, SEQ);
		refTest(w, WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.IF, 0x40);
		getLocal(w, SEQ);
		refCast(w, WasmLispCompiler.TYPE_FARRAY);
		structGet(w, WasmLispCompiler.TYPE_FARRAY, 1);
		setLocal(w, DATA);
		if (simd) {
			// a TYPE_VBLOCK {count, kind, groups}: kind 1 = single (4 bytes), else double
			i32(w, KIND_VBLOCK);
			setLocal(w, KIND);
			getLocal(w, DATA);
			refCast(w, WasmLispCompiler.TYPE_VBLOCK);
			structGet(w, WasmLispCompiler.TYPE_VBLOCK, 0);
			setLocal(w, SIZE);
			getLocal(w, DATA);
			refCast(w, WasmLispCompiler.TYPE_VBLOCK);
			structGet(w, WasmLispCompiler.TYPE_VBLOCK, 1);
			i32(w, 1);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, Type.I32.code());
			i32(w, 4);
			w.write(Instruction.ELSE);
			i32(w, 8);
			w.write(Instruction.END);
			setLocal(w, WIDTH);
		}
		else {
			getLocal(w, DATA);
			refTest(w, WasmLispCompiler.TYPE_F32ARR);
			w.write(Instruction.IF, 0x40);
			i32(w, KIND_F32);
			setLocal(w, KIND);
			i32(w, 4);
			setLocal(w, WIDTH);
			getLocal(w, DATA);
			refCast(w, WasmLispCompiler.TYPE_F32ARR);
			arrayLen(w);
			setLocal(w, SIZE);
			w.write(Instruction.ELSE);
			i32(w, KIND_F64);
			setLocal(w, KIND);
			i32(w, 8);
			setLocal(w, WIDTH);
			getLocal(w, DATA);
			refCast(w, WasmLispCompiler.TYPE_F64ARR);
			arrayLen(w);
			setLocal(w, SIZE);
			w.write(Instruction.END);
		}
		w.write(Instruction.BR, 1); // -> $shaped
		w.write(Instruction.END);
		// a packed integer vector? (the bare array IS the value)
		getLocal(w, SEQ);
		setLocal(w, DATA);
		emitIntVectorShape(w, DATA, WasmLispCompiler.TYPE_I8ARR, KIND_I8, 1, KIND, WIDTH, SIZE);
		emitIntVectorShape(w, DATA, WasmLispCompiler.TYPE_I16ARR, KIND_I16, 2, KIND, WIDTH, SIZE);
		emitIntVectorShape(w, DATA, WasmLispCompiler.TYPE_I32ARR, KIND_I32, 4, KIND, WIDTH, SIZE);
		// anything else: declined
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // $shaped

		// --- the stream: an i31 fd (a negative one is a string stream: declined), or the
		// standard-stream designator (fd 0 in, fd 1 out) ------------------------------
		getLocal(w, STREAM);
		refTest(w, Type.I31.code());
		w.write(Instruction.IF, 0x40);
		getLocal(w, STREAM);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		setLocal(w, FD);
		getLocal(w, FD);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		i32(w, read ? 0 : 1);
		setLocal(w, FD);
		w.write(Instruction.END);

		// --- the bounds: s = start ? i31 : 0 ; e = end ? i31 : size ; a range outside
		// the buffer traps -----------------------------------------------------------
		emitBound(w, START, S, () -> i32(w, 0));
		emitBound(w, END, E, () -> getLocal(w, SIZE));
		getLocal(w, S);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		getLocal(w, E);
		getLocal(w, SIZE);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.I32_OR);
		getLocal(w, S);
		getLocal(w, E);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);

		// --- the staging chunk at HEAP_PTR, reserved over the transfer ---------------
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, SAVED_HP);
		getLocal(w, SAVED_HP);
		i32(w, 7);
		w.write(Instruction.I32_ADD);
		i32(w, -8);
		w.write(Instruction.I32_AND);
		setLocal(w, BUF);
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			getLocal(w, BUF);
			i32(w, CHUNK_BYTES);
			w.write(Instruction.I32_ADD);
		});
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, BUF);
		i32(w, CHUNK_BYTES);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// --- the transfer loop: i walks the elements from s to e --------------------
		getLocal(w, S);
		setLocal(w, I);
		w.write(Instruction.BLOCK, 0x40); // $done
		w.write(Instruction.LOOP, 0x40); // $next
		getLocal(w, I);
		getLocal(w, E);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1); // -> $done
		// n = min(e - i, CHUNK / width) elements this round; need = n * width bytes
		getLocal(w, E);
		getLocal(w, I);
		w.write(Instruction.I32_SUB);
		setLocal(w, N);
		i32(w, CHUNK_BYTES);
		getLocal(w, WIDTH);
		w.write(Instruction.I32_DIV_U);
		getLocal(w, N);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, CHUNK_BYTES);
		getLocal(w, WIDTH);
		w.write(Instruction.I32_DIV_U);
		setLocal(w, N);
		w.write(Instruction.END);
		getLocal(w, N);
		getLocal(w, WIDTH);
		w.write(Instruction.I32_MUL);
		setLocal(w, NEED);
		if (read) {
			// fill the chunk: pos = 0; while (pos < need) { got = fd_read(buf + pos, need
			// -
			// pos); if (got == 0) break; pos += got }
			i32(w, 0);
			setLocal(w, POS);
			w.write(Instruction.BLOCK, 0x40); // $filled
			w.write(Instruction.LOOP, 0x40); // $fill
			getLocal(w, POS);
			getLocal(w, NEED);
			w.write(Instruction.I32_GE_S);
			w.write(Instruction.BR_IF, 1);
			emitIovec(w, IOV, BUF, POS, NEED);
			getLocal(w, FD);
			i32(w, IOV);
			i32(w, 1);
			i32(w, NWRITTEN);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_READ);
			w.write(Instruction.DROP);
			loadMem32(w, NWRITTEN);
			setLocal(w, GOT);
			getLocal(w, GOT);
			w.write(Instruction.I32_EQZ);
			w.write(Instruction.BR_IF, 1);
			getLocal(w, POS);
			getLocal(w, GOT);
			w.write(Instruction.I32_ADD);
			setLocal(w, POS);
			w.write(Instruction.BR, 0);
			w.write(Instruction.END); // $fill
			w.write(Instruction.END); // $filled
			// n = pos / width (a partial trailing element at EOF is dropped)
			getLocal(w, POS);
			getLocal(w, WIDTH);
			w.write(Instruction.I32_DIV_U);
			setLocal(w, N);
			// store the n elements: for (k = 0; k < n; k++) data[i + k] = chunk[k]
			emitElementLoop(w, K, N, () -> emitStoreElement(w, simd, KIND, DATA, I, K, BUF, WIDTH));
			getLocal(w, I);
			getLocal(w, N);
			w.write(Instruction.I32_ADD);
			setLocal(w, I);
			// a short chunk means EOF: stop
			getLocal(w, POS);
			getLocal(w, NEED);
			w.write(Instruction.I32_LT_S);
			w.write(Instruction.BR_IF, 1); // -> $done
		}
		else {
			// stage the n elements: for (k = 0; k < n; k++) chunk[k] = data[i + k]
			emitElementLoop(w, K, N, () -> emitLoadElement(w, simd, KIND, DATA, I, K, BUF, WIDTH));
			// drain the chunk: pos = 0; while (pos < need) { got = fd_write(buf + pos,
			// need -
			// pos); if (got == 0) break; pos += got }
			i32(w, 0);
			setLocal(w, POS);
			w.write(Instruction.BLOCK, 0x40); // $drained
			w.write(Instruction.LOOP, 0x40); // $drain
			getLocal(w, POS);
			getLocal(w, NEED);
			w.write(Instruction.I32_GE_S);
			w.write(Instruction.BR_IF, 1);
			emitIovec(w, IOV, BUF, POS, NEED);
			getLocal(w, FD);
			i32(w, IOV);
			i32(w, 1);
			i32(w, NWRITTEN);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_WRITE);
			w.write(Instruction.DROP);
			loadMem32(w, NWRITTEN);
			setLocal(w, GOT);
			getLocal(w, GOT);
			w.write(Instruction.I32_EQZ);
			w.write(Instruction.BR_IF, 1);
			getLocal(w, POS);
			getLocal(w, GOT);
			w.write(Instruction.I32_ADD);
			setLocal(w, POS);
			w.write(Instruction.BR, 0);
			w.write(Instruction.END); // $drain
			w.write(Instruction.END); // $drained
			// standard output tracks the fresh-line column off the last byte, as
			// _write_byte does: if (fd == 1 && need > 0) LINE_START = (last != '\n')
			getLocal(w, FD);
			i32(w, 1);
			w.write(Instruction.I32_EQ);
			getLocal(w, NEED);
			i32(w, 0);
			w.write(Instruction.I32_GT_S);
			w.write(Instruction.I32_AND);
			w.write(Instruction.IF, 0x40);
			i32(w, WasmLispCompiler.LINE_START_ADDR);
			getLocal(w, BUF);
			getLocal(w, NEED);
			w.write(Instruction.I32_ADD);
			i32(w, 1);
			w.write(Instruction.I32_SUB);
			w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
			i32(w, 10);
			w.write(Instruction.I32_NE);
			w.write(Instruction.I32_STORE, 0x02, 0x00);
			w.write(Instruction.END);
			getLocal(w, I);
			getLocal(w, N);
			w.write(Instruction.I32_ADD);
			setLocal(w, I);
		}
		w.write(Instruction.BR, 0); // -> $next
		w.write(Instruction.END); // $next
		w.write(Instruction.END); // $done

		// --- pop the chunk, answer -------------------------------------------------
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, SAVED_HP);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		if (read) {
			getLocal(w, I);
			w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}
		else {
			getLocal(w, SEQ);
		}
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// if (data is TYPE_xARR) { kind = k; width = bytes; size = array.len; br $shaped }
	private static void emitIntVectorShape(WasmWriter w, int dataSlot, int type, int kind, int bytes, int kindSlot,
			int widthSlot, int sizeSlot) {
		getLocal(w, dataSlot);
		refTest(w, type);
		w.write(Instruction.IF, 0x40);
		i32(w, kind);
		setLocal(w, kindSlot);
		i32(w, bytes);
		setLocal(w, widthSlot);
		getLocal(w, dataSlot);
		refCast(w, type);
		arrayLen(w);
		setLocal(w, sizeSlot);
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);
	}

	// target = arg is null ? dflt : i31.get_s(arg)
	private static void emitBound(WasmWriter w, int argSlot, int targetSlot, Runnable dflt) {
		getLocal(w, argSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, Type.I32.code());
		dflt.run();
		w.write(Instruction.ELSE);
		getLocal(w, argSlot);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.END);
		setLocal(w, targetSlot);
	}

	// iov.ptr = buf + pos ; iov.len = need - pos
	private static void emitIovec(WasmWriter w, int iov, int bufSlot, int posSlot, int needSlot) {
		i32(w, iov);
		getLocal(w, bufSlot);
		getLocal(w, posSlot);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, iov + 4);
		getLocal(w, needSlot);
		getLocal(w, posSlot);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
	}

	// for (k = 0; k < n; k++) body
	private static void emitElementLoop(WasmWriter w, int kSlot, int nSlot, Runnable bodyEmitter) {
		i32(w, 0);
		setLocal(w, kSlot);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, kSlot);
		getLocal(w, nSlot);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		bodyEmitter.run();
		getLocal(w, kSlot);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, kSlot);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// Pushes the byte address of chunk element k: buf + k * width.
	private static void pushChunkAddr(WasmWriter w, int bufSlot, int kSlot, int widthSlot) {
		getLocal(w, bufSlot);
		getLocal(w, kSlot);
		getLocal(w, widthSlot);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
	}

	// Pushes the element index i + k.
	private static void pushIndex(WasmWriter w, int iSlot, int kSlot) {
		getLocal(w, iSlot);
		getLocal(w, kSlot);
		w.write(Instruction.I32_ADD);
	}

	// data[i + k] = chunk[k], by kind.
	private static void emitStoreElement(WasmWriter w, boolean simd, int kindSlot, int dataSlot, int iSlot, int kSlot,
			int bufSlot, int widthSlot) {
		if (simd) {
			// vblock: _v_set(data, i + k, widen(chunk[k])) ; drop the stored value
			getLocal(w, kindSlot);
			i32(w, KIND_VBLOCK);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			getLocal(w, dataSlot);
			pushIndex(w, iSlot, kSlot);
			getLocal(w, widthSlot);
			i32(w, 4);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, Type.F64.code());
			pushChunkAddr(w, bufSlot, kSlot, widthSlot);
			w.write(Instruction.F32_LOAD, 0x00, 0x00);
			w.write(Instruction.F64_PROMOTE_F32);
			w.write(Instruction.ELSE);
			pushChunkAddr(w, bufSlot, kSlot, widthSlot);
			w.write(Instruction.F64_LOAD, 0x00, 0x00);
			w.write(Instruction.END);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_VEC_BASE + WasmVecSimdRuntimeBuilder.V_SET);
			w.write(Instruction.DROP);
			w.write(Instruction.END);
		}
		else {
			emitKindArm(w, kindSlot, KIND_F32, () -> {
				getLocal(w, dataSlot);
				refCast(w, WasmLispCompiler.TYPE_F32ARR);
				pushIndex(w, iSlot, kSlot);
				pushChunkAddr(w, bufSlot, kSlot, widthSlot);
				w.write(Instruction.F32_LOAD, 0x00, 0x00);
				arraySet(w, WasmLispCompiler.TYPE_F32ARR);
			});
			emitKindArm(w, kindSlot, KIND_F64, () -> {
				getLocal(w, dataSlot);
				refCast(w, WasmLispCompiler.TYPE_F64ARR);
				pushIndex(w, iSlot, kSlot);
				pushChunkAddr(w, bufSlot, kSlot, widthSlot);
				w.write(Instruction.F64_LOAD, 0x00, 0x00);
				arraySet(w, WasmLispCompiler.TYPE_F64ARR);
			});
		}
		emitKindArm(w, kindSlot, KIND_I8, () -> {
			getLocal(w, dataSlot);
			refCast(w, WasmLispCompiler.TYPE_I8ARR);
			pushIndex(w, iSlot, kSlot);
			pushChunkAddr(w, bufSlot, kSlot, widthSlot);
			w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
			arraySet(w, WasmLispCompiler.TYPE_I8ARR);
		});
		emitKindArm(w, kindSlot, KIND_I16, () -> {
			getLocal(w, dataSlot);
			refCast(w, WasmLispCompiler.TYPE_I16ARR);
			pushIndex(w, iSlot, kSlot);
			pushChunkAddr(w, bufSlot, kSlot, widthSlot);
			w.write(Instruction.I32_LOAD16_U, 0x00, 0x00);
			arraySet(w, WasmLispCompiler.TYPE_I16ARR);
		});
		emitKindArm(w, kindSlot, KIND_I32, () -> {
			getLocal(w, dataSlot);
			refCast(w, WasmLispCompiler.TYPE_I32ARR);
			pushIndex(w, iSlot, kSlot);
			pushChunkAddr(w, bufSlot, kSlot, widthSlot);
			w.write(Instruction.I32_LOAD, 0x02, 0x00);
			arraySet(w, WasmLispCompiler.TYPE_I32ARR);
		});
	}

	// chunk[k] = data[i + k], by kind.
	private static void emitLoadElement(WasmWriter w, boolean simd, int kindSlot, int dataSlot, int iSlot, int kSlot,
			int bufSlot, int widthSlot) {
		if (simd) {
			// vblock: chunk[k] = narrow?(_v_get(data, i + k))
			getLocal(w, kindSlot);
			i32(w, KIND_VBLOCK);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			getLocal(w, widthSlot);
			i32(w, 4);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			pushChunkAddr(w, bufSlot, kSlot, widthSlot);
			getLocal(w, dataSlot);
			pushIndex(w, iSlot, kSlot);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_VEC_BASE + WasmVecSimdRuntimeBuilder.V_GET);
			w.write(Instruction.F32_DEMOTE_F64);
			w.write(Instruction.F32_STORE, 0x00, 0x00);
			w.write(Instruction.ELSE);
			pushChunkAddr(w, bufSlot, kSlot, widthSlot);
			getLocal(w, dataSlot);
			pushIndex(w, iSlot, kSlot);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(WasmLispCompiler.FUNC_VEC_BASE + WasmVecSimdRuntimeBuilder.V_GET);
			w.write(Instruction.F64_STORE, 0x00, 0x00);
			w.write(Instruction.END);
			w.write(Instruction.END);
		}
		else {
			emitKindArm(w, kindSlot, KIND_F32, () -> {
				pushChunkAddr(w, bufSlot, kSlot, widthSlot);
				getLocal(w, dataSlot);
				refCast(w, WasmLispCompiler.TYPE_F32ARR);
				pushIndex(w, iSlot, kSlot);
				arrayGet(w, WasmLispCompiler.TYPE_F32ARR, Instruction.ARRAY_GET);
				w.write(Instruction.F32_STORE, 0x00, 0x00);
			});
			emitKindArm(w, kindSlot, KIND_F64, () -> {
				pushChunkAddr(w, bufSlot, kSlot, widthSlot);
				getLocal(w, dataSlot);
				refCast(w, WasmLispCompiler.TYPE_F64ARR);
				pushIndex(w, iSlot, kSlot);
				arrayGet(w, WasmLispCompiler.TYPE_F64ARR, Instruction.ARRAY_GET);
				w.write(Instruction.F64_STORE, 0x00, 0x00);
			});
		}
		emitKindArm(w, kindSlot, KIND_I8, () -> {
			pushChunkAddr(w, bufSlot, kSlot, widthSlot);
			getLocal(w, dataSlot);
			refCast(w, WasmLispCompiler.TYPE_I8ARR);
			pushIndex(w, iSlot, kSlot);
			arrayGet(w, WasmLispCompiler.TYPE_I8ARR, Instruction.ARRAY_GET_U);
			w.write(Instruction.I32_STORE8, 0x00, 0x00);
		});
		emitKindArm(w, kindSlot, KIND_I16, () -> {
			pushChunkAddr(w, bufSlot, kSlot, widthSlot);
			getLocal(w, dataSlot);
			refCast(w, WasmLispCompiler.TYPE_I16ARR);
			pushIndex(w, iSlot, kSlot);
			arrayGet(w, WasmLispCompiler.TYPE_I16ARR, Instruction.ARRAY_GET_U);
			w.write(Instruction.I32_STORE16, 0x00, 0x00);
		});
		emitKindArm(w, kindSlot, KIND_I32, () -> {
			pushChunkAddr(w, bufSlot, kSlot, widthSlot);
			getLocal(w, dataSlot);
			refCast(w, WasmLispCompiler.TYPE_I32ARR);
			pushIndex(w, iSlot, kSlot);
			arrayGet(w, WasmLispCompiler.TYPE_I32ARR, Instruction.ARRAY_GET);
			w.write(Instruction.I32_STORE, 0x02, 0x00);
		});
	}

	// if (kind == k) body
	private static void emitKindArm(WasmWriter w, int kindSlot, int kind, Runnable bodyEmitter) {
		getLocal(w, kindSlot);
		i32(w, kind);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		bodyEmitter.run();
		w.write(Instruction.END);
	}

	// === low-level emit helpers ===

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

	private static void loadMem32(WasmWriter w, int addr) {
		i32(w, addr);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

	private static void refTest(WasmWriter w, int heapType) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(heapType);
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

	private static void arrayLen(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
	}

	private static void arraySet(WasmWriter w, int type) {
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(type);
	}

	private static void arrayGet(WasmWriter w, int type, int opcode) {
		w.write(Instruction.GC_PREFIX, opcode);
		w.writeUnsignedLeb128(type);
	}

}
