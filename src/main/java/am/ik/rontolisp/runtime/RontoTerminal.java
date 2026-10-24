package am.ik.rontolisp.runtime;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * Whether a process standard stream (descriptor 0, 1 or 2) is a terminal: the C library's
 * {@code isatty} through FFM, behind {@code %terminal-p} and so
 * {@code interactive-stream-p} on the interpreter and the JVM backend. Java itself cannot
 * tell: {@code Console.isTerminal()} answers for standard input and output together and
 * never for standard error, which is the stream a program colors its diagnostics on.
 *
 * <p>
 * A downcall is a restricted method, which the JDK warns about on standard error unless
 * native access is enabled -- the exec jar and every compiled jar enable it in their
 * manifests. Where it is not enabled ({@code java Prog} without
 * {@code --enable-native-access}) the answer is "no terminal" without the call, so asking
 * never writes the warning. Any failure to bind or call is "no terminal" too.
 *
 * <p>
 * The class imports nothing of rontolisp, so it travels beside a compiled program that
 * asks ({@code .kb/jvm-export.md}, "What travels").
 */
public final class RontoTerminal {

	private RontoTerminal() {
	}

	private static final class Isatty {

		/** {@code int isatty(int)}, or a handle answering 0 where it cannot be bound. */
		static final MethodHandle HANDLE = bind();

		private static MethodHandle bind() {
			MethodHandle never = MethodHandles.dropArguments(MethodHandles.constant(int.class, 0), 0, int.class);
			if (!RontoTerminal.class.getModule().isNativeAccessEnabled()) {
				return never;
			}
			try {
				Linker linker = Linker.nativeLinker();
				// The CRT spells it _isatty on Windows.
				return linker.defaultLookup()
					.find("isatty")
					.or(() -> linker.defaultLookup().find("_isatty"))
					.map(symbol -> linker.downcallHandle(symbol,
							FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)))
					.orElse(never);
			}
			catch (RuntimeException | LinkageError ex) {
				return never;
			}
		}

	}

	/**
	 * Whether the descriptor is a terminal.
	 * @param fd 0, 1 or 2
	 * @return true for a terminal; false for anything else, a descriptor outside the
	 * standard three included
	 */
	public static boolean isTerminal(int fd) {
		if (fd < 0 || fd > 2) {
			return false;
		}
		try {
			return (int) Isatty.HANDLE.invokeExact(fd) != 0;
		}
		catch (Throwable ex) {
			return false;
		}
	}

	/**
	 * The JVM backend's {@code %terminal-p}: {@code T} for a terminal, nil ({@code null})
	 * for anything else, a non-integer included.
	 * @param fd the descriptor, a {@code Long}
	 * @return {@code "T"} or {@code null}
	 */
	// nil is null in the compiled representation; this package cannot import the
	// annotation that would say so (it would travel with the class).
	@SuppressWarnings("NullAway")
	public static Object terminalP(Object fd) {
		return fd instanceof Long n && isTerminal(n.intValue()) ? "T" : null;
	}

}
