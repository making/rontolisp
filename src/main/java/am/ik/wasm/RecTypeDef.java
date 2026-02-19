package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

/**
 * Definition for rec type groups in wasm-GC.
 */
public class RecTypeDef {

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private int count = 0;

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

	public int count() {
		return this.count;
	}

	public byte[] toByteArray() {
		return this.out.toByteArray();
	}

	/**
	 * Writer for struct fields in wasm-GC.
	 */
	public static class StructFieldWriter {

		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		private int count = 0;

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

		public byte[] toByteArray() {
			ByteArrayOutputStream stream = new ByteArrayOutputStream();
			WasmWriter writer = new WasmWriter(stream);
			writer.write(this.count);
			writer.write((Object) this.out.toByteArray());
			return stream.toByteArray();
		}

	}

}
