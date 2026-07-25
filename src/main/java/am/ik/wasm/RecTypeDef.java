package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

/**
 * Definition for rec type groups in wasm-GC.
 */
public class RecTypeDef {

	/** Creates a new empty rec type definition. */
	public RecTypeDef() {
	}

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private int count = 0;

	/**
	 * Add a sub-final function type to this rec group.
	 * @param params the parameter types
	 * @param results the result types
	 * @return this instance for chaining
	 */
	public RecTypeDef addSubFinalFunc(Type[] params, Type[] results) {
		this.count++;
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		WasmWriter writer = new WasmWriter(stream);
		writer.write(Type.SUB_FINAL, 0x00); // sub final, 0 supertypes
		writer.write(Type.FUNC, params.length);
		writer.write((Object) params);
		writer.write(results.length);
		writer.write((Object) results);
		try {
			this.out.write(stream.toByteArray());
		}
		catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		return this;
	}

	/**
	 * Add a sub-final array type to this rec group.
	 * @param consumer a consumer that writes the element field type (storage type
	 * followed by mutability)
	 * @return this instance for chaining
	 */
	public RecTypeDef addSubFinalArray(Consumer<WasmWriter> consumer) {
		this.count++;
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		WasmWriter writer = new WasmWriter(stream);
		writer.write(Type.SUB_FINAL, 0x00); // sub final, 0 supertypes
		writer.write(Type.ARRAY_TYPE);
		consumer.accept(writer);
		try {
			this.out.write(stream.toByteArray());
		}
		catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		return this;
	}

	/**
	 * Add a sub-final struct type to this rec group.
	 * @param consumer a consumer that defines the struct fields
	 * @return this instance for chaining
	 */
	public RecTypeDef addSubFinalStruct(Consumer<StructFieldWriter> consumer) {
		this.count++;
		StructFieldWriter fieldWriter = new StructFieldWriter();
		consumer.accept(fieldWriter);
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		WasmWriter writer = new WasmWriter(stream);
		writer.write(Type.SUB_FINAL, 0x00); // sub final, 0 supertypes
		writer.write(Type.STRUCT_TYPE);
		writer.write((Object) fieldWriter.toByteArray());
		try {
			this.out.write(stream.toByteArray());
		}
		catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		return this;
	}

	/**
	 * Return the number of types in this rec group.
	 * @return the type count
	 */
	public int count() {
		return this.count;
	}

	/**
	 * Serialize this rec group to a byte array.
	 * @return the serialized bytes
	 */
	public byte[] toByteArray() {
		return this.out.toByteArray();
	}

	/**
	 * Writer for struct fields in wasm-GC.
	 */
	public static class StructFieldWriter {

		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		private int count = 0;

		/** Creates a new empty struct field writer. */
		public StructFieldWriter() {
		}

		/**
		 * Add a struct field.
		 * @param mutable whether the field is mutable
		 * @param fieldTypeWriter a consumer that writes the field type
		 * @return this instance for chaining
		 */
		public StructFieldWriter addField(boolean mutable, Consumer<WasmWriter> fieldTypeWriter) {
			this.count++;
			ByteArrayOutputStream stream = new ByteArrayOutputStream();
			WasmWriter writer = new WasmWriter(stream);
			fieldTypeWriter.accept(writer);
			writer.write(mutable ? Mutability.VAR : Mutability.CONST);
			try {
				this.out.write(stream.toByteArray());
			}
			catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
			return this;
		}

		/**
		 * Serialize the fields to a byte array.
		 * @return the serialized bytes
		 */
		public byte[] toByteArray() {
			ByteArrayOutputStream stream = new ByteArrayOutputStream();
			WasmWriter writer = new WasmWriter(stream);
			writer.write(this.count);
			writer.write((Object) this.out.toByteArray());
			return stream.toByteArray();
		}

	}

}
