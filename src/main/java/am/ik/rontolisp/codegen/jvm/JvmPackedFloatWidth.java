package am.ik.rontolisp.codegen.jvm;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

/**
 * The three widths of a packed float array on the JVM backend, and the ONE place that
 * knows how each lays its dimension header out. A packed array is a bare primitive array
 * carrying the header in its own element type ahead of the data:
 *
 * <ul>
 * <li>{@link #DOUBLE}: {@code double[]{rank, dim_0, ..., dim_{rank-1}, e_0, ...}}, data
 * offset {@code 1 + rank};
 * <li>{@link #SINGLE}: {@code float[]} with the same layout, data offset
 * {@code 1 + rank};
 * <li>{@link #BFLOAT16}: {@code short[]{rank, hi_0, lo_0, ..., hi_{rank-1}, lo_{rank-1},
 * e_0, ...}} -- a dimension is an {@code int} and a {@code short} caps at 32767, so each
 * dimension takes TWO slots (its high and low sixteen bits) and the data offset is
 * {@code 1 + 2 * rank}. The rank itself fits one slot.
 * </ul>
 *
 * <p>
 * Every emitter that reads or writes a header goes through these methods rather than
 * spelling {@code 1 + rank} itself: the offset is width-dependent, and a site that
 * hard-codes the two-slot widths' formula reads a length-1 bfloat16 array's rank word as
 * its element -- no exception, a plausible value, and a test that only checks one backend
 * agrees with itself. The trap surfaced on 2026-09-03 and is why this enum exists.
 *
 * <p>
 * Element access at the bfloat16 width goes through the program's own {@code _bf16Value}
 * / {@code _bf16Bits} helpers ({@link JvmFloatArrayRuntimeBuilder}), which mirror
 * {@code am.ik.rontolisp.BFloat16} instruction for instruction so a NaN never crosses the
 * f32 ({@code .kb/bfloat16.md}); the two method references are threaded into
 * {@link #loadElem} / {@link #storeElem} by the caller because the constant pool is the
 * caller's.
 */
enum JvmPackedFloatWidth {

	/** {@code double-float}: a {@code double[]}. */
	DOUBLE("[D"),

	/** {@code single-float}: a {@code float[]}. */
	SINGLE("[F"),

	/** {@code bfloat16}: a {@code short[]} of the top sixteen bits of an f32. */
	BFLOAT16("[S");

	private final String descriptor;

	JvmPackedFloatWidth(String descriptor) {
		this.descriptor = descriptor;
	}

	/** The JVM array class descriptor of this width's backing ({@code [D}, ...). */
	String descriptor() {
		return this.descriptor;
	}

	/**
	 * The backend's width for the language's designator
	 * ({@code am.ik.rontolisp.FloatWidth}, what {@code LispFloatArray.width()} answers):
	 * the two enums are the same three members seen from two sides, and this is the one
	 * mapping between them.
	 * @param width the designator
	 * @return this backend's width
	 */
	static JvmPackedFloatWidth of(am.ik.rontolisp.FloatWidth width) {
		return switch (width) {
			case SINGLE -> SINGLE;
			case DOUBLE -> DOUBLE;
			case BFLOAT16 -> BFLOAT16;
		};
	}

	/** The inverse of {@link #of}. */
	am.ik.rontolisp.FloatWidth floatWidth() {
		return switch (this) {
			case SINGLE -> am.ik.rontolisp.FloatWidth.SINGLE;
			case DOUBLE -> am.ik.rontolisp.FloatWidth.DOUBLE;
			case BFLOAT16 -> am.ik.rontolisp.FloatWidth.BFLOAT16;
		};
	}

	/** The header slots ahead of the data for an array of the given rank. */
	int dataOffset(int rank) {
		return this == BFLOAT16 ? 1 + 2 * rank : 1 + rank;
	}

	/**
	 * The header words of an array with the given dimensions, as ints in header order
	 * (each is stored in the array's own element type -- a {@link #BFLOAT16} word is its
	 * low sixteen bits). For a literal emitter, which knows the dimensions at compile
	 * time.
	 * @param dims the dimension sizes
	 * @return the header words, {@link #dataOffset} of them
	 */
	int[] headerWords(int[] dims) {
		int rank = dims.length;
		int[] words = new int[dataOffset(rank)];
		words[0] = rank;
		for (int k = 0; k < rank; k++) {
			if (this == BFLOAT16) {
				words[1 + 2 * k] = dims[k] >>> 16;
				words[2 + 2 * k] = dims[k] & 0xffff;
			}
			else {
				words[1 + k] = dims[k];
			}
		}
		return words;
	}

	/** Stack: {@code (..., int length) -> (..., arrayref)}: a fresh backing array. */
	void newBacking(JvmAsm a) {
		switch (this) {
			case DOUBLE -> a.newarrayDouble();
			case SINGLE -> a.newarrayFloat();
			case BFLOAT16 -> a.newarrayShort();
		}
	}

	/** Stack: {@code (..., arrayref) -> (..., int)}: the rank from header slot 0. */
	void loadRank(JvmAsm a) {
		a.iconst(0);
		switch (this) {
			case DOUBLE -> {
				a.daload();
				a.d2i();
			}
			case SINGLE -> {
				a.faload();
				a.f2i();
			}
			case BFLOAT16 -> a.saload();
		}
	}

	/**
	 * Stack: {@code (..., arrayref, int k) -> (..., int)}: dimension {@code k} from the
	 * header. At the bfloat16 width the two slots are reassembled, {@code (hi << 16) |
	 * (lo & 0xffff)}.
	 */
	void loadDim(JvmAsm a) {
		switch (this) {
			case DOUBLE -> {
				a.iconst(1);
				a.op(Opcode.IADD);
				a.daload();
				a.d2i();
			}
			case SINGLE -> {
				a.iconst(1);
				a.op(Opcode.IADD);
				a.faload();
				a.f2i();
			}
			case BFLOAT16 -> {
				// (arr, k) -> (arr, k, arr, k) -> (arr, k, lo) -> (lo, arr, k, lo) ->
				// (lo, arr, k) -> (lo, hi << 16) -> (dim)
				a.dup2();
				a.iconst(2);
				a.op(Opcode.IMUL);
				a.iconst(2);
				a.op(Opcode.IADD);
				a.saload();
				emitMaskU16(a);
				a.op(Opcode.DUP_X2);
				a.pop();
				a.iconst(2);
				a.op(Opcode.IMUL);
				a.iconst(1);
				a.op(Opcode.IADD);
				a.saload();
				a.iconst(16);
				a.op(Opcode.ISHL);
				a.op(Opcode.IOR);
			}
		}
	}

	/** Stack: {@code (..., int rank) -> (..., int)}: the data offset for that rank. */
	void emitDataOffset(JvmAsm a) {
		if (this == BFLOAT16) {
			a.iconst(2);
			a.op(Opcode.IMUL);
		}
		a.iconst(1);
		a.op(Opcode.IADD);
	}

	/** Stack: {@code (..., arrayref, int rank) -> (...)}: writes header slot 0. */
	void storeRank(JvmAsm a) {
		a.iconst(0);
		a.swap();
		switch (this) {
			case DOUBLE -> {
				a.i2d();
				a.dastore();
			}
			case SINGLE -> {
				a.i2f();
				a.fastore();
			}
			case BFLOAT16 -> a.sastore();
		}
	}

	/**
	 * Writes dimension {@code k} into the header, from locals: the array reference in
	 * {@code arrSlot}, the dimension index in {@code kSlot}, the size in {@code dimSlot}.
	 * Stack-neutral.
	 */
	void storeDim(JvmAsm a, int arrSlot, int kSlot, int dimSlot) {
		switch (this) {
			case DOUBLE, SINGLE -> {
				a.aload(arrSlot);
				a.iconst(1);
				a.iload(kSlot);
				a.op(Opcode.IADD);
				a.iload(dimSlot);
				if (this == DOUBLE) {
					a.i2d();
					a.dastore();
				}
				else {
					a.i2f();
					a.fastore();
				}
			}
			case BFLOAT16 -> {
				// hi at 1 + 2k, lo at 2 + 2k; sastore keeps the low sixteen bits.
				a.aload(arrSlot);
				a.iload(kSlot);
				a.iconst(2);
				a.op(Opcode.IMUL);
				a.iconst(1);
				a.op(Opcode.IADD);
				a.iload(dimSlot);
				a.iconst(16);
				a.op(Opcode.IUSHR);
				a.sastore();
				a.aload(arrSlot);
				a.iload(kSlot);
				a.iconst(2);
				a.op(Opcode.IMUL);
				a.iconst(2);
				a.op(Opcode.IADD);
				a.iload(dimSlot);
				a.sastore();
			}
		}
	}

	/**
	 * Stack: {@code (..., arrayref, int index) -> (..., double)}: a data element, widened
	 * to a double. A single-float element widens with {@code f2d}; a bfloat16 element
	 * goes through {@code _bf16Value}, never through the f32, so a signalling NaN keeps
	 * its payload.
	 * @param bf16Value the program's {@code _bf16Value(I)D}; required at
	 * {@link #BFLOAT16}, ignored otherwise
	 */
	void loadElem(JvmAsm a, @Nullable MethodrefConstant bf16Value) {
		switch (this) {
			case DOUBLE -> a.daload();
			case SINGLE -> {
				a.faload();
				a.f2d();
			}
			case BFLOAT16 -> {
				a.saload();
				a.invokestatic(Objects.requireNonNull(bf16Value, "_bf16Value"));
			}
		}
	}

	/**
	 * Stack: {@code (..., arrayref, int index, double) -> (...)}: narrows to the backing
	 * width and stores. The bfloat16 narrowing is {@code _bf16Bits}, round to nearest
	 * even with the NaN payload carried by hand.
	 * @param bf16Bits the program's {@code _bf16Bits(D)I}; required at {@link #BFLOAT16},
	 * ignored otherwise
	 */
	void storeElem(JvmAsm a, @Nullable MethodrefConstant bf16Bits) {
		switch (this) {
			case DOUBLE -> a.dastore();
			case SINGLE -> {
				a.d2f();
				a.fastore();
			}
			case BFLOAT16 -> {
				a.invokestatic(Objects.requireNonNull(bf16Bits, "_bf16Bits"));
				a.sastore();
			}
		}
	}

	/**
	 * Stack: {@code (..., double) -> (..., double)}: the value AS STORED -- what a read
	 * of the slot just written answers, so a store's result reflects the narrowing.
	 */
	void emitStoredValue(JvmAsm a, @Nullable MethodrefConstant bf16Value, @Nullable MethodrefConstant bf16Bits) {
		switch (this) {
			case DOUBLE -> {
			}
			case SINGLE -> {
				a.d2f();
				a.f2d();
			}
			case BFLOAT16 -> {
				a.invokestatic(Objects.requireNonNull(bf16Bits, "_bf16Bits"));
				a.invokestatic(Objects.requireNonNull(bf16Value, "_bf16Value"));
			}
		}
	}

	// AND with 0xFFFF: -1 shifted right unsigned by 16 -- iconst() cannot encode 0xFFFF
	// (its SIPUSH fallback is a SIGNED 16-bit immediate, so 65535 would become -1).
	static void emitMaskU16(JvmAsm a) {
		a.iconst(-1);
		a.iconst(16);
		a.op(Opcode.IUSHR);
		a.op(Opcode.IAND);
	}

}
