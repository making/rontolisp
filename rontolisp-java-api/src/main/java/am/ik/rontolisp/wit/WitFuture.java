package am.ik.rontolisp.wit;

/**
 * A WIT {@code future<T>} of a {@link WitImport} interface: the one value its writer
 * sends, read when it is asked for. A {@code future<result<T, E>>} answers the result's
 * ok arm and throws a {@link WitException} carrying the error for its error arm, as a
 * function answering a {@code result} does.
 *
 * <p>
 * One a function answers is read by the program. In a WebAssembly component rontolisp
 * compiles, the host writes the value and {@link #get()} waits for it as a call to the
 * host does. Under a JDK, and on rontolisp's interpreter and JVM, the provider answers a
 * future of its own: {@link #of(Object)}, {@link #failed(WitException)}, or a class of
 * its own implementing this interface.
 *
 * <p>
 * One a {@link WitImport} interface's method answers for an {@code async func}, declared
 * to answer the future of its result, is the call itself, started: {@link #get()} waits
 * for its answer, and a stream handed to the call is written while it runs.
 *
 * <p>
 * One a function takes is the program's: {@link #of(Object)},
 * {@link #failed(WitException)}, or a class of its own. In a component the binding calls
 * {@link #get()} once, before the call, and leaves what it answered or threw where the
 * host finds it whenever it reads the future, within that call or a later one; the call
 * does not wait for the read. Under a JDK, and on rontolisp's interpreter and JVM, the
 * provider is handed the object and calls {@link #get()} when it reads.
 *
 * @param <T> the Java type of the WIT payload type, a result's ok arm for a
 * {@code future<result<T, E>>} ({@link Void} for an ok arm carrying nothing)
 */
public interface WitFuture<T> extends AutoCloseable {

	/**
	 * Waits for the value the writer sends; every later call answers what the first did.
	 * @return the value
	 * @throws WitException for a {@code result}'s error arm
	 * @throws IllegalStateException when the future was closed before it was read, or the
	 * writer went without sending a value
	 */
	T get();

	/**
	 * Gives the readable end back: the writer sees the future dropped, if it is still
	 * unread; a started call not yet read is left to the host, which goes on with it, its
	 * answer unread. Closing a closed future does nothing, and the value a future that
	 * was read answered stays its answer.
	 */
	@Override
	void close();

	/**
	 * A future of a value: a provider's answer, or what a program hands a function.
	 * @param value the value
	 * @param <T> its type
	 * @return the future
	 */
	static <T> WitFuture<T> of(T value) {
		return new WitFuture<>() {

			private boolean read;

			private boolean closed;

			@Override
			public T get() {
				if (this.closed && !this.read) {
					throw new IllegalStateException("The future is closed");
				}
				this.read = true;
				return value;
			}

			@Override
			public void close() {
				this.closed = true;
			}
		};
	}

	/**
	 * A future of a {@code result}'s error arm, a provider's answer or what a program
	 * hands a function: {@link #get()} throws it.
	 * @param error the error arm
	 * @param <T> the type of the ok arm
	 * @return the future
	 */
	static <T> WitFuture<T> failed(WitException error) {
		return new WitFuture<>() {

			private boolean read;

			private boolean closed;

			@Override
			public T get() {
				if (this.closed && !this.read) {
					throw new IllegalStateException("The future is closed");
				}
				this.read = true;
				throw error;
			}

			@Override
			public void close() {
				this.closed = true;
			}
		};
	}

}
