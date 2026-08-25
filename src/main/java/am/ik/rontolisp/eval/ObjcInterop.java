package am.ik.rontolisp.eval;

/**
 * The interpreter's {@code objc} package: the Objective-C runtime and AppKit through the
 * foreign function API, with no JNI, no bundled artifact, no dependency and -- unlike
 * {@code java:} -- no reflection, which is what makes it the one way the
 * {@code rontolisp} native binary can open a window. The surface is a handful of generic
 * verbs named after the foreign system, the exact analogue of {@code java:}:
 * {@code objc:class}, {@code objc:send} (typed by the selector's own encoding),
 * {@code objc:define-class} (a class whose methods are Lisp functions),
 * {@code objc:on-main}, {@code objc:string}, {@code objc:address}, {@code objc:objectp}.
 * The widget layer ({@code appkit:}) is Lisp on top of them ({@link AppKitLibrary}).
 *
 * <p>
 * This class is the ONLY entry into {@link ObjcBridge}, which holds the single reference
 * to {@code am.ik.objc} -- the {@code LinalgGpu} / {@code LinalgGpuKernels} shape, and
 * for the same reason: {@code src/web/java/.../Target_ObjcInterop.java} substitutes these
 * four methods, and with them the whole binding leaves the browser playground's Web Image
 * build. Adding a public method here that touches the bridge breaks that cut.
 *
 * <p>
 * Every failure is a SIGNAL, never a decline: a window that does not open has no
 * fallback. On Linux, on a JVM without native access, for a class or selector that does
 * not exist, for an operand that does not fit, and in a native image for a shape that was
 * not registered at build time, the {@code objc:} function raises an ordinary
 * {@code error} whose message starts with {@code objc:} and says which of those it was.
 *
 * @see ObjcBridge
 * @see AppKitLibrary
 */
public final class ObjcInterop {

	private ObjcInterop() {
	}

	/**
	 * Whether this machine has the Objective-C runtime -- macOS, with native access.
	 * Never throws; the first call opens the binding.
	 * @return {@code true} when the {@code objc:} functions will work
	 */
	public static boolean available() {
		try {
			return ObjcBridge.available();
		}
		catch (Throwable ex) {
			return false;
		}
	}

	/**
	 * What was bound, or why nothing was, in one line.
	 * @return the description
	 */
	public static String description() {
		try {
			return ObjcBridge.description();
		}
		catch (Throwable ex) {
			return "Objective-C is not available: " + ex;
		}
	}

	/**
	 * Defines the {@code objc:} functions in the global environment. They are defined on
	 * every platform and signal at call time where the runtime is absent, so a program
	 * fails at the call that needed a window rather than with an undefined function.
	 * @param globalEnv the global environment
	 * @param caller how a callback applies a Lisp function
	 */
	public static void register(Environment globalEnv, ObjcCaller caller) {
		ObjcBridge.register(globalEnv, caller);
	}

	/**
	 * Whether the process's first thread is the caller's -- a native image on macOS -- so
	 * that the CLI must move its work off and park it in the run loop. Never throws and
	 * costs no AppKit load.
	 * @return {@code true} when {@link #parkMainThread()} must be called
	 */
	public static boolean mainThreadHandOverRequired() {
		try {
			return ObjcBridge.mainThreadHandOverRequired();
		}
		catch (Throwable ex) {
			return false;
		}
	}

	/**
	 * Parks the calling thread -- thread 0 -- in the run loop. Never returns.
	 */
	public static void parkMainThread() {
		ObjcBridge.parkMainThread();
	}

}
