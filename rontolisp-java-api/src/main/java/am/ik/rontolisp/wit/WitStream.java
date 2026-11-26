package am.ik.rontolisp.wit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The readable end of a WIT {@code stream<T>} a function of a {@link WitImport} interface
 * answers: the values its writer sends, in order, read a chunk at a time.
 *
 * <pre>
 * try (WitStream&lt;List&lt;DbValue&gt;&gt; rows = started.rows()) {
 *     for (List&lt;List&lt;DbValue&gt;&gt; chunk = rows.read(100); !chunk.isEmpty(); chunk = rows.read(100)) {
 *         ...
 *     }
 * }
 * </pre>
 *
 * <p>
 * In a WebAssembly component rontolisp compiles, the host writes the values and a read
 * waits for them as a call to the host does. Under a JDK, and on rontolisp's interpreter
 * and JVM, the provider answers a stream of its own: {@link #of(Iterator)}, or a class of
 * its own implementing this interface.
 *
 * @param <T> the Java type of the WIT element type
 */
public interface WitStream<T> extends AutoCloseable {

	/**
	 * Reads the values the writer has sent, at most {@code max} of them, waiting until it
	 * has sent one or has gone.
	 * @param max the most values to read, at least one
	 * @return the values, in order; empty once the writer has gone and every value it
	 * sent is read
	 * @throws IllegalArgumentException when {@code max} is less than one
	 * @throws IllegalStateException when the stream is closed
	 */
	List<T> read(int max);

	/**
	 * Gives the readable end back: the writer sees the stream dropped, and a value it has
	 * not handed over is not read. Closing a closed stream does nothing.
	 */
	@Override
	void close();

	/**
	 * A stream of the values an iterator answers, for a provider: its writer has gone
	 * once the iterator has no more.
	 * @param values the values, in order
	 * @param <T> the type of the values
	 * @return the stream
	 */
	static <T> WitStream<T> of(Iterator<? extends T> values) {
		return new WitStream<>() {

			private boolean closed;

			@Override
			public List<T> read(int max) {
				if (max < 1) {
					throw new IllegalArgumentException("A read of " + max + " values");
				}
				if (this.closed) {
					throw new IllegalStateException("The stream is closed");
				}
				List<T> read = new ArrayList<>();
				while (read.size() < max && values.hasNext()) {
					read.add(values.next());
				}
				return read;
			}

			@Override
			public void close() {
				this.closed = true;
			}
		};
	}

}
