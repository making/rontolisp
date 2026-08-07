package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

/**
 * Base class for WASM section structures that consist of a counted list of entries.
 *
 * @param <T> the concrete subclass type for method chaining
 */
public class CountingDef<T extends CountingDef<?>> {

	/** Creates a new empty counting definition. */
	public CountingDef() {
	}

	/** The output stream collecting serialized entries. */
	protected final ByteArrayOutputStream out = new ByteArrayOutputStream();

	/** The number of entries added. */
	protected int count = 0;

	/**
	 * Add an entry written by the given consumer.
	 * @param consumer a consumer that writes the entry
	 * @return this instance for chaining
	 */
	@SuppressWarnings("unchecked")
	public T add(Consumer<WasmWriter> consumer) {
		final ByteArrayOutputStream stream = new ByteArrayOutputStream();
		final WasmWriter out = new WasmWriter(stream);
		this.count++;
		consumer.accept(out);
		try {
			this.out.write(stream.toByteArray());
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return (T) this;
	}

	/**
	 * Whether no entry was added -- a section built from this holds an empty vector and
	 * therefore says nothing an absent section does not.
	 * @return true when the definition has no entries
	 */
	protected final boolean isEmpty() {
		return this.count == 0;
	}

	/**
	 * Serialize the count and all entries to a byte array.
	 * @return the serialized bytes
	 */
	protected final byte[] toByteArray() {
		final ByteArrayOutputStream stream = new ByteArrayOutputStream();
		final WasmWriter out = new WasmWriter(stream);
		// The entry count is a LEB128 integer; a raw byte would be malformed for
		// sections with 128 or more entries.
		out.writeUnsignedLeb128(this.count);
		out.write((Object) this.out.toByteArray());
		return stream.toByteArray();
	}

}
