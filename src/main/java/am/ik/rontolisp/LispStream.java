package am.ik.rontolisp;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * An asynchronous stream value: an ordered sequence of chunks plus an end-of-stream
 * marker. Every built-in producer (a {@code rontolisp:fetch} response body, an
 * {@code rontolisp:http-handler} request body) yields string chunks, while user streams
 * from {@code rontolisp:make-stream} may carry any non-nil values.
 * {@code rontolisp:stream-read} yields a future settling to the next chunk ({@code nil}
 * once closed and drained), {@code rontolisp:stream-write} appends a chunk (its future
 * settles when the stream accepted it), {@code rontolisp:stream-close} ends the stream,
 * and {@code rontolisp:streamp} tests for this type. Opaque: no reader syntax, prints as
 * {@code #&lt;STREAM&gt;}.
 *
 * <p>
 * This class carries the interpreter's representation (the JVM compiler and the WASM
 * backends use their own runtime structures). A stream comes in one of two modes:
 * <ul>
 * <li><em>push</em> ({@link #open()}, {@link #settled}): chunks are buffered without
 * bound, so a write is accepted immediately, and a read with no buffered chunk parks as a
 * pending {@link CompletableFuture} completed directly by the next write or by
 * close;</li>
 * <li><em>pull</em> ({@link #pull}): there is no buffer and no write end at all -- each
 * read runs a read thunk that answers the next chunk, and the first {@code nil} chunk
 * ends the stream and runs the close thunk. This is the mode
 * {@code rontolisp::%stream-new} builds, the one shape every backend shares (a read
 * thunk, a close thunk and a drained flag).</li>
 * </ul>
 */
public final class LispStream implements LispVal {

	private final ArrayDeque<LispVal> chunks = new ArrayDeque<>();

	private final ArrayDeque<CompletableFuture<LispVal>> pendingReads = new ArrayDeque<>();

	/**
	 * The pull mode's read thunk, or {@code null} in push mode. It answers a SETTLED
	 * chunk -- resolving a future the underlying Lisp thunk produced is the caller's job,
	 * so this class never sees one.
	 */
	private final @Nullable Supplier<LispVal> readFn;

	/** The pull mode's close thunk, or {@code null} in push mode. Runs at most once. */
	private final @Nullable Runnable closeFn;

	private boolean closed;

	private @Nullable RuntimeException failure;

	private LispStream(@Nullable Supplier<LispVal> readFn, @Nullable Runnable closeFn) {
		this.readFn = readFn;
		this.closeFn = closeFn;
	}

	/**
	 * Creates a fresh open stream.
	 * @return the stream value
	 */
	public static LispStream open() {
		return new LispStream(null, null);
	}

	/**
	 * Creates an already-closed stream holding a single chunk, for producers whose whole
	 * content is available up front.
	 * @param chunk the one chunk
	 * @return the stream value
	 */
	public static LispStream settled(LispVal chunk) {
		LispStream stream = new LispStream(null, null);
		stream.chunks.add(chunk);
		stream.closed = true;
		return stream;
	}

	/**
	 * Creates a PULL stream over a read thunk and a close thunk: nothing is buffered and
	 * there is no write end, so a chunk exists only once a read asks for it. The chunks
	 * come from wherever the caller's thunk gets them, which is what lets a transport
	 * hand over a body it has not received yet.
	 * @param readFn answers the next chunk, or {@code nil} at end of stream (a chunk the
	 * underlying computation produced asynchronously must already be resolved -- see
	 * {@link #readFn})
	 * @param closeFn runs once, at end of stream or at {@link #close()}, whichever comes
	 * first
	 * @return the stream value
	 */
	public static LispStream pull(Supplier<LispVal> readFn, Runnable closeFn) {
		return new LispStream(readFn, closeFn);
	}

	/**
	 * Takes the next chunk.
	 * @return a future settling to the next chunk, or to {@code nil} once the stream is
	 * closed and drained
	 */
	public CompletableFuture<LispVal> read() {
		if (this.readFn != null) {
			return pullRead(this.readFn);
		}
		return bufferedRead();
	}

	// The pull mode's read: one thunk call per read, outside the monitor (it runs
	// arbitrary Lisp, up to and including a read of this very stream). The first nil
	// chunk ends the stream and runs the close protocol, so a drain closes exactly once
	// and a read past the end is nil -- the contract every backend shares.
	private CompletableFuture<LispVal> pullRead(Supplier<LispVal> pullFn) {
		synchronized (this) {
			if (this.closed) {
				return CompletableFuture.completedFuture(LispNil.INSTANCE);
			}
		}
		LispVal chunk = pullFn.get();
		if (chunk instanceof LispNil) {
			close();
			return CompletableFuture.completedFuture(LispNil.INSTANCE);
		}
		return CompletableFuture.completedFuture(chunk);
	}

	private synchronized CompletableFuture<LispVal> bufferedRead() {
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
	 * @throws IllegalStateException if the stream is already closed, or is a pull stream
	 * (which has no write end); the message is the caller's diagnostic
	 */
	public void write(LispVal chunk) {
		CompletableFuture<LispVal> waiter;
		synchronized (this) {
			if (this.readFn != null) {
				throw new IllegalStateException("the stream has no write end");
			}
			if (this.closed) {
				throw new IllegalStateException("the stream is closed");
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
	 * them observe end of stream ({@code nil}). A pull stream runs its close thunk here.
	 * Closing twice is a no-op.
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
		if (this.closeFn != null) {
			this.closeFn.run();
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
