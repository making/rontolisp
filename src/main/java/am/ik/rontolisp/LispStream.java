package am.ik.rontolisp;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

/**
 * An asynchronous stream value: an ordered sequence of chunks plus an end-of-stream
 * marker. One value owns both the read and the write end; every built-in producer (a
 * {@code rontolisp:fetch} response body, an {@code rontolisp:http-handler} request body)
 * yields string chunks, while user streams from {@code rontolisp:make-stream} may carry
 * any non-nil values. {@code rontolisp:stream-read} yields a future settling to the next
 * chunk ({@code nil} once closed and drained), {@code rontolisp:stream-write} appends a
 * chunk (its future settles when the stream accepted it), {@code rontolisp:stream-close}
 * ends the stream, and {@code rontolisp:streamp} tests for this type. Opaque: no reader
 * syntax, prints as {@code #&lt;STREAM&gt;}.
 *
 * <p>
 * This class carries the interpreter's representation (the JVM compiler and the WASM
 * component backend use their own runtime structures). Chunks are buffered without bound,
 * so a write is accepted immediately; a read with no buffered chunk parks as a pending
 * {@link CompletableFuture} completed directly by the next write or by close.
 */
public final class LispStream implements LispVal {

	private final ArrayDeque<LispVal> chunks = new ArrayDeque<>();

	private final ArrayDeque<CompletableFuture<LispVal>> pendingReads = new ArrayDeque<>();

	private boolean closed;

	private @Nullable RuntimeException failure;

	private LispStream() {
	}

	/**
	 * Creates a fresh open stream.
	 * @return the stream value
	 */
	public static LispStream open() {
		return new LispStream();
	}

	/**
	 * Creates an already-closed stream holding a single chunk, for producers whose whole
	 * content is available up front.
	 * @param chunk the one chunk
	 * @return the stream value
	 */
	public static LispStream settled(LispVal chunk) {
		LispStream stream = new LispStream();
		stream.chunks.add(chunk);
		stream.closed = true;
		return stream;
	}

	/**
	 * Takes the next chunk.
	 * @return a future settling to the next chunk, or to {@code nil} once the stream is
	 * closed and drained
	 */
	public synchronized CompletableFuture<LispVal> read() {
		if (!this.chunks.isEmpty()) {
			return CompletableFuture.completedFuture(this.chunks.poll());
		}
		if (this.failure != null) {
			return CompletableFuture.failedFuture(this.failure);
		}
		if (this.closed) {
			return CompletableFuture.completedFuture(LispNil.INSTANCE);
		}
		CompletableFuture<LispVal> pending = new CompletableFuture<>();
		this.pendingReads.add(pending);
		return pending;
	}

	/**
	 * Appends a chunk, completing a pending read directly when one is parked.
	 * @param chunk the chunk (never {@code nil}; the caller validates)
	 * @throws IllegalStateException if the stream is already closed
	 */
	public void write(LispVal chunk) {
		CompletableFuture<LispVal> waiter;
		synchronized (this) {
			if (this.closed) {
				throw new IllegalStateException("stream is closed");
			}
			waiter = this.pendingReads.poll();
			if (waiter == null) {
				this.chunks.add(chunk);
			}
		}
		if (waiter != null) {
			waiter.complete(chunk);
		}
	}

	/**
	 * Closes the write end. Buffered chunks stay readable; pending and later reads past
	 * them observe end of stream ({@code nil}). Closing twice is a no-op.
	 */
	public void close() {
		List<CompletableFuture<LispVal>> drained;
		synchronized (this) {
			if (this.closed) {
				return;
			}
			this.closed = true;
			drained = List.copyOf(this.pendingReads);
			this.pendingReads.clear();
		}
		for (CompletableFuture<LispVal> pending : drained) {
			pending.complete(LispNil.INSTANCE);
		}
	}

	/**
	 * Fails the stream: buffered chunks stay readable, but once drained every pending and
	 * later read settles with the given error instead of end of stream. Used by built-in
	 * producers (e.g. a transport error mid-way through a {@code rontolisp:fetch}
	 * response body) so consumers observe the failure at the read that would otherwise
	 * block. A no-op after {@link #close()} or a previous failure.
	 * @param error the error each read re-signals (the eval layer's condition-carrying
	 * exception)
	 */
	public void fail(RuntimeException error) {
		List<CompletableFuture<LispVal>> drained;
		synchronized (this) {
			if (this.closed || this.failure != null) {
				return;
			}
			this.failure = error;
			this.closed = true;
			drained = List.copyOf(this.pendingReads);
			this.pendingReads.clear();
		}
		for (CompletableFuture<LispVal> pending : drained) {
			pending.completeExceptionally(error);
		}
	}

	/**
	 * Returns whether the write end has been closed.
	 * @return {@code true} once {@link #close()} has run
	 */
	public synchronized boolean isClosed() {
		return this.closed;
	}

	@Override
	public String print() {
		return "#<STREAM>";
	}

}
