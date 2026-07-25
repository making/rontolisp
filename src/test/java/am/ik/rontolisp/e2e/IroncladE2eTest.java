package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the SHA-256 / HMAC / PBKDF2
 * slice of the REAL ironclad v0.61 sources (vendored unmodified under
 * {@code src/test/resources/ironclad}, BSD 3-Clause) loads via {@code asdf:load-system}
 * and reproduces published test vectors on all four backends via
 * {@link AsdfLibraryE2eSupport}.
 *
 * <p>
 * ironclad's own {@code ironclad.asd} is an executable program (it defines component
 * classes and generates its defsystems with a macro), which the data-only {@code .asd}
 * front end cannot read; {@code eval/AsdOverrides} substitutes a bundled replacement
 * declaring exactly the loadable slice, so the component files loaded here are the
 * library's real ones. The vendored tree keeps the original {@code ironclad.asd}
 * alongside, both as provenance and so the substitution itself is exercised.
 *
 * <p>
 * The vectors: SHA-256 of {@code "abc"} and of a two-block message (FIPS 180-2 appendix
 * B), SHA-224 of {@code "abc"}, HMAC-SHA-256 of RFC 4231 test case 2 through BOTH the
 * {@code make-mac} API and the deprecated {@code make-hmac} one, PBKDF2-HMAC-SHA-256 at 1
 * and 4096 iterations, and finally RFC 7677's SCRAM-SHA-256 {@code SaltedPassword} -- the
 * value that makes this slice the cl-postgres SCRAM authentication dependency.
 */
class IroncladE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "ironclad")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :ironclad)
			(print (ironclad:byte-array-to-hex-string
			        (ironclad:digest-sequence :sha256 (ironclad:ascii-string-to-byte-array "abc"))))
			(print (ironclad:byte-array-to-hex-string
			        (ironclad:digest-sequence :sha256
			          (ironclad:ascii-string-to-byte-array
			           "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))))
			(print (ironclad:byte-array-to-hex-string
			        (ironclad:digest-sequence :sha224 (ironclad:ascii-string-to-byte-array "abc"))))
			(let ((mac (ironclad:make-mac :hmac (ironclad:ascii-string-to-byte-array "Jefe") :sha256)))
			  (ironclad:update-mac mac (ironclad:ascii-string-to-byte-array "what do ya want for nothing?"))
			  (print (ironclad:byte-array-to-hex-string (ironclad:produce-mac mac))))
			(let ((hmac (ironclad:make-hmac (ironclad:ascii-string-to-byte-array "Jefe") :sha256)))
			  (ironclad:update-hmac hmac (ironclad:ascii-string-to-byte-array "what do ya want for nothing?"))
			  (print (ironclad:byte-array-to-hex-string (ironclad:hmac-digest hmac))))
			(let ((kdf (ironclad:make-kdf :pbkdf2 :digest :sha256)))
			  (print (ironclad:byte-array-to-hex-string
			          (ironclad:derive-key kdf (ironclad:ascii-string-to-byte-array "password")
			                               (ironclad:ascii-string-to-byte-array "salt") 1 32)))
			  (print (ironclad:byte-array-to-hex-string
			          (ironclad:derive-key kdf (ironclad:ascii-string-to-byte-array "password")
			                               (ironclad:ascii-string-to-byte-array "salt") 4096 32)))
			  (print (ironclad:byte-array-to-hex-string
			          (ironclad:derive-key kdf (ironclad:ascii-string-to-byte-array "pencil")
			                               (ironclad:hex-string-to-byte-array "5b6d99689d12358eeca04b141236fa81")
			                               4096 32))))
			""";

	private static final List<String> EXPECTED = List.of(
			"\"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\"",
			"\"248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1\"",
			"\"23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7\"",
			"\"5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843\"",
			"\"5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843\"",
			"\"120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b\"",
			"\"c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a\"",
			"\"c4a49510323ab4f952cac1fa99441939e78ea74d6be81ddf7096e87513dc615d\"");

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
		return "IroncladE2e";
	}

}
