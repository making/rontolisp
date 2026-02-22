package am.ik.jvm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

/**
 * Base class for JVM class file structures that consist of a counted list of entries.
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
	public T add(Consumer<ByteCodeWriter> consumer) {
		final ByteArrayOutputStream stream = new ByteArrayOutputStream();
		final ByteCodeWriter out = new ByteCodeWriter(stream);
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
	 * Serialize the count and all entries to a byte array.
	 * @return the serialized bytes
	 */
	protected final byte[] toByteArray() {
		final ByteArrayOutputStream stream = new ByteArrayOutputStream();
		final ByteCodeWriter out = new ByteCodeWriter(stream);
		out.writeU2(this.count);
		out.write(this.out.toByteArray());
		return stream.toByteArray();
	}

}
