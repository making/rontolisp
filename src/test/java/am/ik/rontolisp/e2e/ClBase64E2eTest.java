package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the REAL cl-base64 v3.4
 * sources (vendored unmodified under {@code src/test/resources/cl-base64}, BSD) load via
 * {@code asdf:load-system} and encode/decode on all four backends via
 * {@link AsdfLibraryE2eSupport}. The library exercises the macro-time
 * {@code (intern (concatenate ...))} function-name synthesis (current-package
 * {@code intern} + marker-free {@code symbol-name}), the {@code (setf (schar s i) c)}
 * string mutation ({@code %schar-set} on the interpreter, the setq-rebuild lowering on
 * the compiled backends), {@code locally}, string-as-character-array {@code make-array}/
 * {@code aref}, and the {@code (apply #'error ...)} signal path (typed condition on the
 * interpreter, datum-only lite on the compiled backends -- both caught by the same
 * {@code handler-case}). The integer round-trip value stays inside the WASM {@code i31}
 * range (a larger integer degrades to a float there).
 */
class ClBase64E2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "cl-base64")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :cl-base64)
			(print (cl-base64:string-to-base64-string "Hello, World!"))
			(print (cl-base64:base64-string-to-string "SGVsbG8sIFdvcmxkIQ=="))
			(print (cl-base64:string-to-base64-string "Hello, World!" :columns 5))
			(print (cl-base64:string-to-base64-string "Hello?>>" :uri t))
			(print (cl-base64:base64-string-to-string "SGVsbG8_Pj4." :uri t))
			(print (cl-base64:usb8-array-to-base64-string
			        (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(1 2 3))))
			(print (cl-base64:base64-string-to-usb8-array "AQID"))
			(print (cl-base64:integer-to-base64-string 1234567))
			(print (cl-base64:base64-string-to-integer "EtaH"))
			(print (handler-case (cl-base64:base64-string-to-string "SGVsbG8@")
			         (error (e) :caught-bad-char)))
			""";

	private static final List<String> EXPECTED = List.of("\"SGVsbG8sIFdvcmxkIQ==\"", "\"Hello, World!\"", "\"SGVsb",
			"G8sIF", "dvcmx", "kIQ==\"", "\"SGVsbG8_Pj4.\"", "\"Hello?>>\"", "\"AQID\"", "#(1 2 3)", "\"EtaH\"",
			"1234567", ":CAUGHT-BAD-CHAR");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected String exercise() {
		return EXERCISE;
	}

	@Override
	protected List<String> expected() {
		return EXPECTED;
	}

	@Override
	protected String artifactName() {
		return "ClBase64E2e";
	}

}
