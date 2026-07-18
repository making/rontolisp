package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Disabled;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the REAL md5 v2.0.4 sources
 * (vendored unmodified under {@code src/test/resources/md5}, public domain) load via
 * {@code asdf:load-system} and digest the RFC 1321 A.5 test vectors on the interpreter
 * and the JVM backend via {@link AsdfLibraryE2eSupport}. The library exercises the
 * two-argument {@code (float i 0.0d0)} prototype call, {@code logandc1}/{@code logandc2}/
 * {@code logorc2}, {@code deftype} no-ops, {@code macrolet}, a {@code defmacro}-defined
 * accessor as a {@code setf} place, the BOA {@code (:constructor make-md5-state ())}
 * defstruct option with {@code :type}/{@code :read-only} slot options, a top-level
 * {@code (locally ...)} wrapper around the defstruct, {@code etypecase} over
 * {@code (simple-array (unsigned-byte 8) (*))}/{@code simple-string}, and the
 * flexi-streams shim's {@code string-to-octets} (via {@code md5sum-string} under
 * {@code char-code-limit} &gt; 256).
 *
 * <p>
 * The two WASM backends are excluded: the MD5 working state is unsigned 32-bit arithmetic
 * ({@code #xEFCDAB89} magic constants, {@code (ldb (byte 32 0) ...)} sums), which does
 * not fit the WASM {@code i31} fixnum range.
 */
class Md5E2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "md5").toAbsolutePath().toString();

	private static final String EXERCISE = """
			(asdf:load-system :md5)
			(defun hex (digest)
			  (string-downcase
			   (with-output-to-string (s)
			     (dotimes (i (length digest))
			       (format s "~2,'0X" (aref digest i))))))
			(print (hex (md5:md5sum-sequence "")))
			(print (hex (md5:md5sum-sequence "abc")))
			(print (hex (md5:md5sum-sequence "message digest")))
			(print (hex (md5:md5sum-sequence "abcdefghijklmnopqrstuvwxyz")))
			(print (hex (md5:md5sum-sequence "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")))
			(print (hex (md5:md5sum-sequence "12345678901234567890123456789012345678901234567890123456789012345678901234567890")))
			(let ((v (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(97 98 99))))
			  (print (hex (md5:md5sum-sequence v))))
			(print (hex (md5:md5sum-string "abc")))
			(print (hex (md5:md5sum-string "日本語")))
			(let ((state (md5:make-md5-state)))
			  (md5:update-md5-state state "ab")
			  (md5:update-md5-state state "c")
			  (print (hex (md5:finalize-md5-state state))))
			""";

	private static final List<String> EXPECTED = List.of("\"d41d8cd98f00b204e9800998ecf8427e\"",
			"\"900150983cd24fb0d6963f7d28e17f72\"", "\"f96b697d7cb7938d525a2f31aaf161d0\"",
			"\"c3fcd3d76192e4007dfb496cca67e13b\"", "\"d174ab98d277d9f5a5611c2c9f419d9f\"",
			"\"57edf4a22be3c955ac49da2e2107b67a\"", "\"900150983cd24fb0d6963f7d28e17f72\"",
			"\"900150983cd24fb0d6963f7d28e17f72\"", "\"00110af8b4393ef3f72c50be5b332bec\"",
			"\"900150983cd24fb0d6963f7d28e17f72\"");

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
		return "Md5E2e";
	}

	@Override
	@Disabled("md5 needs unsigned 32-bit arithmetic beyond the WASM i31 fixnum range")
	void compilesAndRunsOnWasmPreview1() {
	}

	@Override
	@Disabled("md5 needs unsigned 32-bit arithmetic beyond the WASM i31 fixnum range")
	void compilesAndRunsOnWasmComponent() {
	}

}
