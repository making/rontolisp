package am.ik.wasm;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Writer for WASM binary format.
 */
public final class WasmWriter {

	private final OutputStream out;

	/**
	 * Create a new writer that writes to the given output stream.
	 * @param out the target output stream
	 */
	public WasmWriter(OutputStream out) {
		this.out = out;
	}

	/**
	 * Write a 4-byte little-endian integer.
	 * @param i the value to write
	 * @return this instance for chaining
	 */
	public WasmWriter writeLittleEndian4(int i) {
		return this.write((Object) ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array());
	}

	/**
	 * Write a 64-bit float in little-endian format.
	 * @param value the double value to write
	 * @return this instance for chaining
	 */
	public WasmWriter writeF64(double value) {
		return this.write((Object) ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array());
	}

	/**
	 * Write an integer using signed LEB128 encoding.
	 * <p>
	 * <strong>Pick by the FIELD, not by the value's sign.</strong> Almost every integer
	 * in the binary format is a {@code u32} -- every index (function, local, global,
	 * type, field, label, tag, memory), every count, every length and size prefix, every
	 * memarg component -- and those must go through {@link #writeUnsignedLeb128}. A
	 * signed encoding of a non-negative value is still ACCEPTED there (the format
	 * tolerates padding up to the type's width), so the mistake costs bytes rather than
	 * validity: any value whose low seven bits have bit 6 set needs a redundant
	 * continuation byte to keep the sign bit clear, i.e. every index in [64, 127] costs
	 * two bytes instead of one.
	 * <p>
	 * The genuinely signed fields are the {@code i32.const} / {@code i64.const}
	 * immediates, the {@code s33} of a heap type or blocktype (written by
	 * {@link #writeHeapType}), and the component model's {@code valtype}, whose sign IS
	 * the discriminator between a primitive and a type index.
	 * @param i the value to write
	 * @return this instance for chaining
	 */
	public WasmWriter writeSignedLeb128(int i) {
		// https://en.wikipedia.org/wiki/LEB128#Encode_signed_32-bit_integer
		int value = i;
		value |= 0;
		final List<Byte> result = new ArrayList<>();
		while (true) {
			final int b = value & 0x7f;
			value >>= 7;
			if ((value == 0 && (b & 0x40) == 0) || (value == -1 && (b & 0x40) != 0)) {
				result.add((byte) b);
				break;
			}
			result.add((byte) (b | 0x80));
		}
		return this.write(result);
	}

	/**
	 * Write a long using signed LEB128 encoding (e.g. an {@code i64.const} immediate
	 * whose value does not fit in 32 bits).
	 * @param i the value to write
	 * @return this instance for chaining
	 */
	public WasmWriter writeSignedLeb128(long i) {
		long value = i;
		final List<Byte> result = new ArrayList<>();
		while (true) {
			final int b = (int) (value & 0x7f);
			value >>= 7;
			if ((value == 0 && (b & 0x40) == 0) || (value == -1 && (b & 0x40) != 0)) {
				result.add((byte) b);
				break;
			}
			result.add((byte) (b | 0x80));
		}
		return this.write(result);
	}

	/**
	 * Write an integer using unsigned LEB128 encoding -- the {@code u32} of the binary
	 * format, hence every index, count, length and size prefix. See
	 * {@link #writeSignedLeb128} for the short list of fields that are NOT this.
	 * @param i the value to write
	 * @return this instance for chaining
	 */
	public WasmWriter writeUnsignedLeb128(int i) {
		int value = i;
		final List<Byte> result = new ArrayList<>();
		do {
			int b = value & 0x7f;
			value >>>= 7;
			if (value != 0) {
				b |= 0x80;
			}
			result.add((byte) b);
		}
		while (value != 0);
		return this.write(result);
	}

	/**
	 * Write a reference type with nullability and heap type, in the shortest legal
	 * encoding.
	 * <p>
	 * A NULLABLE reference to an ABSTRACT heap type has a one-byte shorthand -- the heap
	 * type's own code IS the value type ({@code 6D} is {@code eqref}, not just
	 * {@code eq}) -- and this method takes it, so {@code (ref null eq)} costs one byte
	 * rather than the two of {@code 63 6D}. The saving is per OCCURRENCE and reference
	 * types are most of what this emitter writes (every local declaration, blocktype,
	 * global, struct field and function signature), which is why the shorthand is worth a
	 * method rather than a call-site choice: it is 2.4%-5.7% of every module the project
	 * emits.
	 * <p>
	 * The shorthand exists ONLY for that pairing. A non-nullable {@code (ref eq)} keeps
	 * its {@code 64} constructor byte, and a concrete type index keeps its constructor
	 * byte in either nullability -- an index is an s33 that would otherwise be
	 * indistinguishable from an abstract code.
	 * @param nullable whether the reference is nullable
	 * @param heapType the heap type index or abstract code
	 * @return this instance for chaining
	 */
	public WasmWriter writeRefType(boolean nullable, int heapType) {
		if (nullable && isAbstractHeapType(heapType)) {
			return this.write(heapType);
		}
		this.write(nullable ? Type.REFNULL.code() : Type.REF.code());
		return this.writeHeapType(heapType);
	}

	/**
	 * Whether the code is an abstract heap type rather than a concrete type index. The
	 * abstract codes occupy the contiguous range {@code 0x69-0x74}: {@code exn}=0x69,
	 * {@code array}=0x6A, {@code struct}=0x6B, {@code i31}=0x6C, {@code eq}=0x6D,
	 * {@code any}=0x6E, {@code extern}=0x6F, {@code func}=0x70, {@code none}=0x71,
	 * {@code noextern}=0x72, {@code nofunc}=0x73, {@code noexn}=0x74.
	 * @param heapType the heap type code or index
	 * @return true when the code names an abstract heap type
	 */
	private static boolean isAbstractHeapType(int heapType) {
		return heapType >= Type.EXNREF.code() && heapType <= Type.NOEXN.code();
	}

	/**
	 * Write a heap type (abstract or concrete index). The two share one int parameter,
	 * disambiguated by range: every abstract heap type code lives in {@code 0x60-0x7F}
	 * ({@code exn}=0x69 through {@code noexn}=0x74), so values there encode as the
	 * negative single-byte form and anything below is a concrete type index. A module
	 * whose {@code ref.test}/{@code ref.cast}/{@code ref.null} targets ever reach type
	 * index 0x60 (96) would collide with the abstract range -- the emitter keeps its
	 * runtime types well below that.
	 * @param heapType the heap type code or index
	 * @return this instance for chaining
	 */
	public WasmWriter writeHeapType(int heapType) {
		if (heapType >= 0x60) {
			// Abstract heap type (e.g. i31=0x6C, eq=0x6D, any=0x6E)
			// Convert to signed value so LEB128 produces the correct single byte
			return this.writeSignedLeb128(heapType - 0x80);
		}
		// Concrete type index
		return this.writeSignedLeb128(heapType);
	}

	/**
	 * Write a sequence of objects to the output.
	 * @param objects the objects to write (Integer, Byte, byte[], String, Codable,
	 * Object[], or List)
	 * @return this instance for chaining
	 */
	public WasmWriter write(Object... objects) {
		try {
			for (Object o : objects) {
				if (o instanceof Integer) {
					out.write((Integer) o);
				}
				else if (o instanceof Byte) {
					out.write((Byte) o);
				}
				else if (o instanceof byte[]) {
					out.write((byte[]) o);
				}
				else if (o instanceof String) {
					out.write(((String) o).getBytes(StandardCharsets.UTF_8));
				}
				else if (o instanceof Codable) {
					out.write(((Codable) o).code());
				}
				else if (o instanceof Object[]) {
					this.write((Object[]) o);
				}
				else if (o instanceof List<?>) {
					((List<?>) o).forEach(this::write);
				}
				else {
					throw new IllegalStateException(o.getClass() + " is not supported");
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return this;
	}

	/**
	 * Write a section with a counting definition.
	 * @param section the section type
	 * @param consumer a consumer that populates the definition
	 * @return this instance for chaining
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public WasmWriter writeSection(Section section, Consumer<CountingDef> consumer) {
		return this.writeSection(section, consumer, CountingDef::new);
	}

	/**
	 * Write a section with a typed counting definition. A section the consumer left EMPTY
	 * is not written at all: every one of these is a vector, and the format gives an
	 * absent section and a section holding zero entries the same meaning, so the three
	 * bytes of an empty one are dead weight. (A conditional emission at the call site can
	 * therefore just leave the definition empty instead of guarding the whole call.)
	 * @param <T> the definition type
	 * @param section the section type
	 * @param consumer a consumer that populates the definition
	 * @param defSupplier a supplier for the definition instance
	 * @return this instance for chaining
	 */
	public <T extends CountingDef<T>> WasmWriter writeSection(Section section, Consumer<T> consumer,
			Supplier<T> defSupplier) {
		final T def = defSupplier.get();
		consumer.accept(def);
		if (def.isEmpty()) {
			return this;
		}
		final byte[] bytes = def.toByteArray();
		// A section's size prefix is a u32, like every other length and index in the
		// binary format -- see the encoding note on writeSignedLeb128.
		return this.write(section).writeUnsignedLeb128(bytes.length).write(bytes);
	}

	/**
	 * Write the type section.
	 * @param consumer a consumer that populates the type definitions
	 * @return this instance for chaining
	 */
	public WasmWriter writeTypeSection(Consumer<TypeDef> consumer) {
		return this.writeSection(Section.TYPE, consumer, TypeDef::new);
	}

	/**
	 * Write the import section.
	 * @param consumer a consumer that populates the import definitions
	 * @return this instance for chaining
	 */
	public WasmWriter writeImportSection(Consumer<ImportDef> consumer) {
		return this.writeSection(Section.IMPORT, consumer, ImportDef::new);
	}

	/**
	 * Write the function section.
	 * @param consumer a consumer that populates the function definitions
	 * @return this instance for chaining
	 */
	public WasmWriter writeFunction(Consumer<FunctionDef> consumer) {
		return this.writeSection(Section.FUNCTION, consumer, FunctionDef::new);
	}

	/**
	 * Write the memory section.
	 * @param consumer a consumer that populates the memory definitions
	 * @return this instance for chaining
	 */
	public WasmWriter writeMemory(Consumer<MemoryDef> consumer) {
		return this.writeSection(Section.MEMORY, consumer, MemoryDef::new);
	}

	/**
	 * Write the tag section (exception-handling proposal). In the binary encoding it
	 * belongs between the memory section and the global section.
	 * @param consumer a consumer that populates the tag definitions
	 * @return this instance for chaining
	 */
	public WasmWriter writeTagSection(Consumer<TagDef> consumer) {
		return this.writeSection(Section.TAG, consumer, TagDef::new);
	}

	/**
	 * Write the global section.
	 * @param consumer a consumer that populates the global definitions
	 * @return this instance for chaining
	 */
	public WasmWriter writeGlobal(Consumer<GlobalDef> consumer) {
		return this.writeSection(Section.GLOBAL, consumer, GlobalDef::new);
	}

	/**
	 * Write the export section.
	 * @param consumer a consumer that populates the export definitions
	 * @return this instance for chaining
	 */
	public WasmWriter writeExport(Consumer<ExportDef> consumer) {
		return this.writeSection(Section.EXPORT, consumer, ExportDef::new);
	}

	/**
	 * Write the start section (id 8): the single function the engine runs at
	 * instantiation, after the data segments install and before any export can be called.
	 * In the binary encoding it belongs between the export section (7) and the
	 * element/code sections (9/10). The named function's type must be {@code () -> ()}.
	 * @param funcIndex the start function's index
	 * @return this instance for chaining
	 */
	public WasmWriter writeStartSection(int funcIndex) {
		final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
		new WasmWriter(body).writeUnsignedLeb128(funcIndex);
		final byte[] bytes = body.toByteArray();
		return this.write(Section.START).writeUnsignedLeb128(bytes.length).write(bytes);
	}

	/**
	 * Write the code section.
	 * @param consumer a consumer that populates the code definitions
	 * @return this instance for chaining
	 */
	public WasmWriter writeCode(Consumer<CodeDef> consumer) {
		return this.writeSection(Section.CODE, consumer, CodeDef::new);
	}

	/**
	 * Write the data section.
	 * @param consumer a consumer that populates the data definitions
	 * @return this instance for chaining
	 */
	public WasmWriter writeDataSection(Consumer<DataDef> consumer) {
		return this.writeSection(Section.DATA, consumer, DataDef::new);
	}

}
