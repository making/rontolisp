package am.ik.rontolisp.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;

import org.jspecify.annotations.Nullable;

/**
 * Standard output, error and input per THREAD, so tests that run a compiled program in
 * process can run their methods concurrently.
 *
 * <p>
 * {@link System#setOut} and friends are process-wide: two concurrent tests that each swap
 * in a capture stream read each other's output. A compiled class reads
 * {@code System.out}/{@code err}/{@code in} with a {@code getstatic} at every use, so
 * this class installs ONE routing stream per slot, once, and a test points its own
 * thread's slot at a target for the length of a {@code try}:
 *
 * <pre>
 * try (var _ = ThreadStdio.out(baos)) {
 * 	main.invoke(null, (Object) new String[0]);
 * }
 * </pre>
 *
 * <p>
 * The slots are {@link InheritableThreadLocal}s, so a thread the program STARTS
 * ({@code make-thread}, the {@code --parallel} workers, the sized-stack main thread)
 * writes where its creator writes. A thread that is REUSED is not covered: a pool thread
 * created before the scope (the ForkJoin common pool, whose workers inherit nothing)
 * writes to the process's original stream, so its output is missing from the capture
 * rather than landing in another test's -- a red test, never a wrong green one.
 *
 * <p>
 * Output is encoded in {@link Charset#defaultCharset()}, which is what the
 * {@code new PrintStream(baos)} it replaces used, so {@code baos.toString()} decodes it
 * unchanged.
 */
public final class ThreadStdio {

	private static final InheritableThreadLocal<@Nullable OutputStream> OUT = new InheritableThreadLocal<>();

	private static final InheritableThreadLocal<@Nullable OutputStream> ERR = new InheritableThreadLocal<>();

	private static final InheritableThreadLocal<@Nullable InputStream> IN = new InheritableThreadLocal<>();

	private ThreadStdio() {
	}

	/**
	 * Sends this thread's (and its future children's) {@code System.out} to
	 * {@code target} until the returned scope closes.
	 * @param target where the bytes go
	 * @return the scope restoring the previous target
	 */
	public static Scope out(OutputStream target) {
		install();
		return Scope.enter(OUT, target);
	}

	/**
	 * Sends this thread's (and its future children's) {@code System.err} to
	 * {@code target} until the returned scope closes.
	 * @param target where the bytes go
	 * @return the scope restoring the previous target
	 */
	public static Scope err(OutputStream target) {
		install();
		return Scope.enter(ERR, target);
	}

	/**
	 * Feeds this thread's (and its future children's) {@code System.in} from
	 * {@code source} until the returned scope closes.
	 * @param source what reads answer
	 * @return the scope restoring the previous source
	 */
	public static Scope in(InputStream source) {
		install();
		return Scope.enter(IN, source);
	}

	/**
	 * Installs the routing streams unless they already are the process's. Re-checked on
	 * every entry so a test that swapped a stream in and out the old way cannot leave the
	 * router unplugged.
	 */
	private static synchronized void install() {
		if (!(System.out instanceof RoutedPrintStream)) {
			System.setOut(new RoutedPrintStream(System.out, OUT));
		}
		if (!(System.err instanceof RoutedPrintStream)) {
			System.setErr(new RoutedPrintStream(System.err, ERR));
		}
		if (!(System.in instanceof RoutedInputStream)) {
			System.setIn(new RoutedInputStream(System.in, IN));
		}
	}

	/** One slot's redirection; closing it restores what the thread had before. */
	public static final class Scope implements AutoCloseable {

		private final InheritableThreadLocal<?> slot;

		private final @Nullable Object previous;

		private final Thread owner = Thread.currentThread();

		private Scope(InheritableThreadLocal<?> slot, @Nullable Object previous) {
			this.slot = slot;
			this.previous = previous;
		}

		private static <T> Scope enter(InheritableThreadLocal<@Nullable T> slot, T target) {
			Scope scope = new Scope(slot, slot.get());
			slot.set(target);
			return scope;
		}

		@Override
		@SuppressWarnings("unchecked")
		public void close() {
			if (Thread.currentThread() != this.owner) {
				throw new IllegalStateException("a stdio scope must close on the thread that opened it");
			}
			if (this.previous == null) {
				this.slot.remove();
			}
			else {
				((InheritableThreadLocal<Object>) this.slot).set(this.previous);
			}
		}

	}

	/**
	 * The process's {@code System.out}/{@code err}: bytes go to the calling thread's
	 * target, or to the stream it replaced. {@link #close()} only flushes, since the one
	 * instance serves every thread.
	 */
	private static final class RoutedPrintStream extends PrintStream {

		RoutedPrintStream(PrintStream fallback, InheritableThreadLocal<@Nullable OutputStream> slot) {
			super(new OutputStream() {

				private OutputStream target() {
					OutputStream target = slot.get();
					return (target != null) ? target : fallback;
				}

				@Override
				public void write(int b) throws IOException {
					target().write(b);
				}

				@Override
				public void write(byte[] b, int off, int len) throws IOException {
					target().write(b, off, len);
				}

				@Override
				public void flush() throws IOException {
					target().flush();
				}
			}, false, Charset.defaultCharset());
		}

		@Override
		public void close() {
			flush();
		}

	}

	/** The process's {@code System.in}: reads come from the calling thread's source. */
	private static final class RoutedInputStream extends InputStream {

		private final InputStream fallback;

		private final InheritableThreadLocal<@Nullable InputStream> slot;

		RoutedInputStream(InputStream fallback, InheritableThreadLocal<@Nullable InputStream> slot) {
			this.fallback = fallback;
			this.slot = slot;
		}

		private InputStream source() {
			InputStream source = this.slot.get();
			return (source != null) ? source : this.fallback;
		}

		@Override
		public int read() throws IOException {
			return source().read();
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			return source().read(b, off, len);
		}

		@Override
		public long skip(long n) throws IOException {
			return source().skip(n);
		}

		@Override
		public int available() throws IOException {
			return source().available();
		}

		@Override
		public void close() throws IOException {
			InputStream source = this.slot.get();
			if (source != null) {
				source.close();
			}
		}

	}

}
