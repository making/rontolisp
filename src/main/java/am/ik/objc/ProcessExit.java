package am.ik.objc;

/**
 * A callback body's failure that means "end the process with this status code" rather
 * than an ordinary error to report -- {@code uiop:quit} inside a method or a block, on
 * the one backend whose exit unwinds through Java frames as an exception instead of
 * calling the host's exit primitive directly ({@code System.exit} on the JVM backends,
 * {@code proc_exit} / {@code wasi:cli/exit} on the two WASM ones, none of which ever
 * reach {@link ObjcMethods#fail}). {@link ObjcMethods#fail} ends the process right where
 * the callback stands instead of reporting and answering zero: quitting means the same
 * thing on every backend, and the signal's own Lisp source has already finished standard
 * output before raising it, so nothing is lost by not unwinding through the native frame
 * above -- which {@link ObjcMethods} never does for any escape, this one included.
 */
public interface ProcessExit {

	/**
	 * The process status code to end with.
	 * @return the status code
	 */
	int code();

}
