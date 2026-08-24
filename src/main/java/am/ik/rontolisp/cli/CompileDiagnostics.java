package am.ik.rontolisp.cli;

import java.util.function.Supplier;

import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.reader.LispReadException;

/**
 * Turns a compile-path failure into a diagnostic that names the form that failed.
 * <p>
 * The frontend records where every cons was read from, so a pass that fails long after
 * the read -- a macro body that signals, an operator no backend knows, a malformed
 * binding list a walker casts and fails on -- can still name {@code file:line:column}
 * instead of leaving the user a bare message about a program that may be a hundred
 * spliced files. Compile path only: see {@link SourceProvenance} for why the interpreter
 * deliberately does not record.
 * <p>
 * Shared by the CLI and by {@link JvmSourceCompiler}, so an embedder's diagnostic is the
 * one the command line prints.
 */
final class CompileDiagnostics {

	private CompileDiagnostics() {
	}

	/**
	 * Runs a compile with provenance recording on, re-reporting a failure at the position
	 * of the form that failed.
	 * @param <T> what the compile produces
	 * @param compile the compile to run
	 * @return whatever the compile produced
	 */
	static <T> T recording(Supplier<T> compile) {
		SourceProvenance.startRecording();
		try {
			return compile.get();
		}
		catch (RuntimeException ex) {
			throw locate(ex);
		}
		finally {
			SourceProvenance.stopRecording();
		}
	}

	/**
	 * Re-reports a frontend failure at the position of the form that failed. The original
	 * exception becomes the cause, so nothing about it is lost; a read error is left
	 * alone because {@link LispReadException} already carries its own prefix, and so is a
	 * failure whose position is unknown (nothing was recorded, or the failing form was
	 * entirely macro-generated) -- prefixing nothing would only add noise.
	 * @param ex the failure
	 * @return the failure to throw in its place
	 */
	static RuntimeException locate(RuntimeException ex) {
		if (ex instanceof LispReadException) {
			return ex;
		}
		SourceLocation location = SourceProvenance.failureLocation(ex);
		String prefix = location == null ? "" : location.prefix();
		if (prefix.isEmpty()) {
			return ex;
		}
		return new LispCompileException(prefix + ex.getMessage(), ex);
	}

}
