package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the two-function stream runtime of the degenerate (non-asyncMode) tier:
 * {@code _p1_stream_read} and {@code _p1_stream_close} over a {@code TYPE_P1_STREAM}
 * struct {@code {mut i32 eof, mut readFn, mut closeFn}} -- the same three fields as the
 * {@code --component} tier's {@code TYPE_WASI_STREAM}, because nothing about a stream
 * value is WASI: it is a read thunk, a close thunk and a drained flag.
 *
 * <p>
 * The block is emitted only when {@code rontolisp::%stream-new} appears in the program
 * (its one producer), so every other module is byte-identical to a build that never knew
 * about it.
 *
 * <p>
 * The read thunk is PULLED once per {@code rontolisp:stream-read}, and its answer is
 * resolved through {@code _p1_future_await} before the end-of-stream test: on this tier a
 * suspending host import ({@code wasm-import ... :async t}) and an {@code async-lambda}
 * both answer a settled {@code TYPE_P1_FUTURE}, and a future wrapping nil is not nil --
 * without the resolve such a thunk could never report EOF. A plain value passes through
 * the resolver unchanged, so a synchronous thunk costs one call.
 */
final class WasmP1StreamRuntimeBuilder {

	/** Number of functions this block appends (in {@link #build} index order). */
	static final int FUNC_COUNT = 2;

	/**
	 * {@code _p1_stream_read(stream) -> future}: the {@code rontolisp:stream-read} of a
	 * {@code TYPE_P1_STREAM}. Always a SETTLED future -- nothing on this tier can suspend
	 * -- of the next chunk, or of nil once drained; the first nil chunk flips {@code eof}
	 * and runs the close protocol once.
	 */
	static final int OFF_READ = 0;

	/**
	 * {@code _p1_stream_close(stream) -> nil}: the {@code rontolisp:stream-close} of a
	 * {@code TYPE_P1_STREAM} -- flips {@code eof} and runs the close thunk, once (a
	 * drained or already-closed stream is a no-op).
	 */
	static final int OFF_CLOSE = 1;

	private WasmP1StreamRuntimeBuilder() {
	}

	/**
	 * Returns the function type index of block member {@code off}. Both members are
	 * {@code ((ref null eq)) -> (ref null eq)}, so both reuse the arity-0 callable
	 * signature and this block appends no type of its own.
	 * @param off the {@code OFF_*} member offset
	 * @return the type index
	 */
	static int typeIndexOf(int off) {
		return WasmLispCompiler.TYPE_CALLABLE_BASE;
	}

	/**
	 * Builds the body of block member {@code off}.
	 * @param off the {@code OFF_*} member offset
	 * @param streamType the {@code TYPE_P1_STREAM} type index
	 * @return the function body bytes (locals declaration included)
	 */
	static byte[] build(int off, int streamType) {
		return switch (off) {
			case OFF_READ -> buildRead(streamType);
			case OFF_CLOSE -> buildClose(streamType);
			default -> throw new IllegalArgumentException("unknown p1 stream runtime member: " + off);
		};
	}

	// _p1_stream_read (stream) -> a settled future of the next chunk (nil = EOF).
	private static byte[] buildRead(int streamType) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int S = 0, CHUNK = 1;
		// locals: 1x (ref null eq)
		w.write(1);
		w.writeUnsignedLeb128(1);
		w.writeRefType(true, Type.EQ.code());
		// Drained already: a settled-nil future.
		castStream(w, S, streamType);
		structGet(w, streamType, 0);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		settledFuture(w, () -> refNullEq(w));
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// CHUNK = await(dispatch_0(readFn)) -- see the class comment for the await.
		castStream(w, S, streamType);
		structGet(w, streamType, 1);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_DISPATCH_BASE);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_P1_FUTURE_AWAIT);
		setLocal(w, CHUNK);
		// EOF: flip eof and run the close protocol once.
		getLocal(w, CHUNK);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		emitCloseOnce(w, S, streamType);
		w.write(Instruction.END);
		settledFuture(w, () -> getLocal(w, CHUNK));
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _p1_stream_close (stream) -> nil: run the close protocol once.
	private static byte[] buildClose(int streamType) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int S = 0;
		w.write(0); // no locals
		castStream(w, S, streamType);
		structGet(w, streamType, 0);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		emitCloseOnce(w, S, streamType);
		w.write(Instruction.END);
		refNullEq(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// eof = 1; dispatch_0(closeFn); drop.
	private static void emitCloseOnce(WasmWriter w, int slot, int streamType) {
		castStream(w, slot, streamType);
		i32(w, 1);
		structSet(w, streamType, 0);
		castStream(w, slot, streamType);
		structGet(w, streamType, 2);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_DISPATCH_BASE);
		w.write(Instruction.DROP);
	}

	// struct.new TYPE_P1_FUTURE {2, <value>} -- the settled degenerate future, the same
	// shape %async-run and a suspending import wrapper produce.
	private static void settledFuture(WasmWriter w, Runnable value) {
		i32(w, 2);
		value.run();
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_P1_FUTURE);
	}

	private static void castStream(WasmWriter w, int slot, int streamType) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(streamType);
	}

	private static void structGet(WasmWriter w, int type, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(type);
		w.writeUnsignedLeb128(field);
	}

	private static void structSet(WasmWriter w, int type, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeUnsignedLeb128(type);
		w.writeUnsignedLeb128(field);
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

	private static void refNullEq(WasmWriter w) {
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
	}

}
