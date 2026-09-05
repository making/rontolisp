package am.ik.rontolisp.eval;

import am.ik.rontolisp.FloatArrayAccessHook;
import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispQuantizedMatrix;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispVal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;

/**
 * The packed buffers {@code %read-sequence-packed} / {@code %write-sequence-packed} move
 * in bulk, viewed as a flat row-major element sequence of a fixed byte width: a packed
 * float array of any rank (f32 = 4 bytes, f64 = 8 bytes) or a packed
 * {@code (unsigned-byte 8|16|32)} vector (1 / 2 / 4 bytes). Elements are little-endian on
 * the wire.
 *
 * <p>
 * {@code objc:data} / {@code objc:bytes} hand the same bytes to Objective-C
 * ({@link ObjcBridge}), so the two operators share this one definition of what a packed
 * buffer's bytes are: what {@code write-sequence} writes from a {@code #f} matrix is
 * byte-for-byte what {@code objc:data} puts in an {@code NSData}.
 *
 * @param value the buffer
 * @param width one element's byte width
 * @param size the element count
 */
record PackedBuffer(LispVal value, int width, int size) {

	/**
	 * The buffer a value is, or {@code null} when it is not a packed one -- the declining
	 * protocol both sequence primitives answer nil with.
	 * @param value the candidate
	 * @return the buffer, or {@code null}
	 */
	static @Nullable PackedBuffer of(LispVal value) {
		if (value instanceof LispSingleFloatArray f) {
			return new PackedBuffer(f, 4, f.totalSize());
		}
		if (value instanceof LispDoubleFloatArray d) {
			return new PackedBuffer(d, 8, d.totalSize());
		}
		if (value instanceof LispIntVector iv) {
			return new PackedBuffer(iv, iv.width() / 8, iv.length());
		}
		if (value instanceof LispQuantizedMatrix qm) {
			// The ggml blocks as they are: one element = one byte, so a GGUF tensor is
			// one transfer in and a written matrix is what llama.cpp reads back.
			return new PackedBuffer(qm, 1, qm.blocks().length);
		}
		return null;
	}

	/**
	 * A {@code :start} / {@code :end} operand.
	 * @param op the operator, for the message
	 * @param v the operand
	 * @param dflt what nil means
	 * @return the bound
	 */
	static int bound(String op, LispVal v, int dflt) {
		if (v instanceof LispNil) {
			return dflt;
		}
		if (v instanceof LispInteger i) {
			return (int) i.value();
		}
		throw new LispEvalException(op + ": :start/:end must be an integer or nil");
	}

	/**
	 * The whole buffer's byte size.
	 * @return the size in bytes
	 */
	int byteSize() {
		return this.size * this.width;
	}

	/**
	 * The whole buffer's bytes, little-endian.
	 * @return a fresh array of {@link #byteSize()} bytes
	 */
	byte[] bytes() {
		byte[] bytes = new byte[byteSize()];
		store(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), 0, this.size);
		return bytes;
	}

	void load(ByteBuffer bytes, int start, int n) {
		// A bulk read writes a packed float array's storage IN PLACE, behind the
		// element setter's back, so it reports the write itself and writes into what
		// the hook answers: under --gpu the device may hold a resident copy of this
		// very array, or its only copy, and the storage may be a stub (.kb/gpu.md).
		switch (this.value) {
			case LispSingleFloatArray f ->
				bytes.asFloatBuffer().get((float[]) FloatArrayAccessHook.written(f.storage()), start, n);
			case LispDoubleFloatArray d ->
				bytes.asDoubleBuffer().get((double[]) FloatArrayAccessHook.written(d.storage()), start, n);
			case LispIntVector iv -> {
				long[] data = iv.data();
				for (int k = 0; k < n; k++) {
					data[start + k] = switch (iv.width()) {
						case 8 -> bytes.get() & 0xFFL;
						case 16 -> bytes.getShort() & 0xFFFFL;
						default -> bytes.getInt() & 0xFFFF_FFFFL;
					};
				}
			}
			case LispQuantizedMatrix qm -> bytes.get(qm.blocks(), start, n);
			default -> throw new IllegalStateException();
		}
	}

	void store(ByteBuffer bytes, int start, int n) {
		switch (this.value) {
			case LispSingleFloatArray f -> bytes.asFloatBuffer().put(f.data(), start, n);
			case LispDoubleFloatArray d -> bytes.asDoubleBuffer().put(d.data(), start, n);
			case LispIntVector iv -> {
				long[] data = iv.data();
				for (int k = 0; k < n; k++) {
					long e = data[start + k];
					switch (iv.width()) {
						case 8 -> bytes.put((byte) e);
						case 16 -> bytes.putShort((short) e);
						default -> bytes.putInt((int) e);
					}
				}
			}
			case LispQuantizedMatrix qm -> bytes.put(qm.blocks(), start, n);
			default -> throw new IllegalStateException();
		}
	}

}
