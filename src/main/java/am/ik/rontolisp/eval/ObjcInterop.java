package am.ik.rontolisp.eval;

import java.util.List;
import java.util.function.BiFunction;

import am.ik.rontolisp.LispVal;

/**
 * The interpreter's entry to the {@code objc} package: the Objective-C runtime and AppKit
 * through the foreign function API, with no JNI, no bundled artifact, no dependency and
 * -- unlike {@code java:} -- no reflection, which is what makes it the one way the
 * {@code rontolisp} native binary can open a window. The vocabulary is Lisp
 * ({@code objc.lisp}, {@link ObjcLibrary}); what this entry registers is the primitive
 * layer under it ({@link ObjcPrimitives}: {@code objc::%send} and friends, and
 * {@code objc:on-main}). The widget layer ({@code appkit:}) is Lisp on top
 * ({@link AppKitLibrary}).
 *
 * <p>
 * This class is the ONLY entry into {@link ObjcPrimitives}, which holds the single
 * reference to {@code am.ik.objc} -- the {@code LinalgGpu} / {@code LinalgGpuKernels}
 * shape, and for the same reason: {@code src/web/java/.../Target_ObjcInterop.java}
 * substitutes these methods, and with them the whole binding leaves the browser
 * playground's Web Image build. Adding a public method here that touches the primitives
 * breaks that cut.
 *
 * <p>
 * Every failure is a SIGNAL, never a decline: a window that does not open has no
 * fallback. On Linux, on a JVM without native access, for a class or selector that does
 * not exist, for an operand that does not fit, and in a native image for a shape that was
 * not registered at build time, the call raises an ordinary {@code error} whose message
 * starts with {@code objc:} and says which of those it was.
 *
 * @see ObjcPrimitives
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
			return ObjcPrimitives.available();
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
			return ObjcPrimitives.description();
		}
		catch (Throwable ex) {
			return "Objective-C is not available: " + ex;
		}
	}

	/**
	 * Defines the primitive layer ({@code objc::%send} and friends, and
	 * {@code objc:on-main}) that {@code objc.lisp} is written over. Defined on every
	 * platform; each signals at call time where the runtime is absent, so a program fails
	 * at the call that needed a window rather than with an undefined function.
	 * @param globalEnv the global environment
	 * @param apply how {@code objc:on-main}, a method or a block applies a Lisp function
	 */
	public static void registerPrimitives(Environment globalEnv, BiFunction<LispVal, List<LispVal>, LispVal> apply) {
		ObjcPrimitives.register(globalEnv, apply);
	}

	/**
	 * Whether the process's first thread is the caller's -- a native image on macOS -- so
	 * that the CLI must move its work off and park it in the run loop. Never throws and
	 * costs no AppKit load.
	 * @return {@code true} when {@link #parkMainThread()} must be called
	 */
	public static boolean mainThreadHandOverRequired() {
		try {
			return ObjcPrimitives.mainThreadHandOverRequired();
		}
		catch (Throwable ex) {
			return false;
		}
	}

	/**
	 * Parks the calling thread -- thread 0 -- in the run loop. Never returns.
	 */
	public static void parkMainThread() {
		ObjcPrimitives.parkMainThread();
	}

}
