package am.ik.rontolisp.wit;

/**
 * The readable end of a WIT {@code future<T>} a function of a {@link WitImport} interface
 * answers: the one value its writer sends, read when it is asked for. A
 * {@code future<result<T, E>>} answers the result's ok arm and throws a
 * {@link WitException} carrying the error for its error arm, as a function answering a
 * {@code result} does.
 *
 * <p>
 * In a WebAssembly component rontolisp compiles, the host writes the value and
 * {@link #get()} waits for it as a call to the host does. Under a JDK, and on rontolisp's
 * interpreter and JVM, the provider answers a future of its own: {@link #of(Object)},
 * {@link #failed(WitException)}, or a class of its own implementing this interface.
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
	 * unread. Closing a closed future does nothing, and the value a future that was read
	 * answered stays its answer.
	 */
	@Override
	void close();

	/**
	 * A future of a value, for a provider.
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
	 * A future of a {@code result}'s error arm, for a provider: {@link #get()} throws it.
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
