package am.ik.rontolisp.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs a compile with its {@code System.err} captured and fails on any
 * {@code warning: ... is undefined} line -- per THREAD, so several corpus compiles may
 * run at once and each still sees only its own warnings.
 *
 * <p>
 * <b>A test that prints a compile warning must assert on it.</b> When the corpus lost its
 * {@code TokenizersLibrary} splice, the WASM guard compiled fifteen undefined-call
 * warnings to standard output and PASSED -- the program was broken, the breakage was on
 * the console, and nobody reads the output of a green test (.todo/688). Warnings reach
 * {@code System.err} whether a backend buffers them per attempt or prints them straight
 * through ({@code compiler/CompileWarnings}), so capturing that stream catches both.
 *
 * <p>
 * {@code System.err} is one process-wide slot, so a plain {@code setErr}/restore pair
 * cannot serve two compiles at once: the second would capture the first's warnings, and
 * whichever restored last would leave the other's stream installed. While any capture is
 * open, the slot instead holds one router that sends a write to the capturing thread's
 * own buffer and everything else to the stream that was there before. A compile runs on
 * the thread that called it (no pass forks work), so the thread is the right key.
 */
public final class UndefinedWarnings {

	private static final ThreadLocal<@Nullable ByteArrayOutputStream> CAPTURE = new ThreadLocal<>();

	private static final Object LOCK = new Object();

	private static int open;

	@Nullable private static PrintStream saved;

	private UndefinedWarnings() {
	}

	/**
	 * Runs {@code compile} with this thread's {@code System.err} captured, echoes what it
	 * printed, and asserts that none of it is an undefined-function warning.
	 * @param <T> the compile's result type
	 * @param compile the compile to run
	 * @return whatever it produced
	 */
	public static <T> T forbid(Supplier<T> compile) {
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		PrintStream original = open();
		CAPTURE.set(captured);
		T result;
		try {
			result = compile.get();
		}
		finally {
			CAPTURE.remove();
			close();
		}
		String warnings = captured.toString(StandardCharsets.UTF_8);
		original.print(warnings);
		assertThat(
				warnings.lines().filter(line -> line.contains("warning: ") && line.contains("is undefined")).toList())
			.as("undefined-function warnings from the corpus compile: a name the corpus "
					+ "reaches is not being spliced, so the pass pipeline is wrong")
			.isEmpty();
		return result;
	}

	private static PrintStream open() {
		synchronized (LOCK) {
			if (open++ == 0) {
				PrintStream before = System.err;
				saved = before;
				System.setErr(new PrintStream(new Router(before), true, StandardCharsets.UTF_8));
			}
			return java.util.Objects.requireNonNull(saved);
		}
	}

	private static void close() {
		synchronized (LOCK) {
			if (--open == 0) {
				System.setErr(java.util.Objects.requireNonNull(saved));
				saved = null;
			}
		}
	}

	/** Sends a write to the calling thread's capture when it has one. */
	private static final class Router extends OutputStream {

		private final OutputStream fallback;

		Router(OutputStream fallback) {
			this.fallback = fallback;
		}

		@Override
		public void write(int b) throws IOException {
			ByteArrayOutputStream capture = CAPTURE.get();
			if (capture != null) {
				capture.write(b);
			}
			else {
				this.fallback.write(b);
			}
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			ByteArrayOutputStream capture = CAPTURE.get();
			if (capture != null) {
				capture.write(b, off, len);
			}
			else {
				this.fallback.write(b, off, len);
			}
		}

		@Override
		public void flush() throws IOException {
			if (CAPTURE.get() == null) {
				this.fallback.flush();
			}
		}

	}

}
