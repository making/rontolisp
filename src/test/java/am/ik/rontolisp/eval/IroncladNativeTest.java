package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The interpreter's always-on native PBKDF2 ({@link IroncladNative} over
 * {@link Sha2Kernels}), which takes a SCRAM-SHA-256 PostgreSQL connect from ~17 s to
 * milliseconds.
 *
 * <p>
 * Two things are checked. (1) The interception actually FIRES -- without the
 * {@code #<function ...>} guard the wiring could be silently dead and every vector below
 * would still pass on ironclad's Lisp code ([[simd-shadow-and-dead-flag-lesson]]). (2)
 * The kernel agrees with two INDEPENDENT oracles -- the published RFC 6070 / RFC 7914
 * vectors and the JDK's own {@code PBKDF2WithHmacSHA*} / {@code MessageDigest} -- plus,
 * for every input the kernel declines, that ironclad's own defun still runs and produces
 * its own error text unchanged.
 *
 * <p>
 * {@code IroncladE2eTest} is the cross-backend half of the same contract: its three
 * compiled legs keep running ironclad's Lisp inner loop over the same vectors, so the
 * equivalence is pinned from both sides.
 */
class IroncladNativeTest {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "ironclad")
		.toAbsolutePath()
		.toString();

	private static final String LOAD = "(asdf:load-system :ironclad)";

	private LispVal eval(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.setSystemPath(List.of(SYSTEM_DIR));
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(LOAD + "\n" + input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	/** Runs {@code pbkdf2-derive-key} through ironclad and returns the hex digest. */
	private String deriveKey(String digest, String password, String salt, int iterations, int keyLength) {
		String form = "(ironclad:byte-array-to-hex-string (ironclad::pbkdf2-derive-key %s (ironclad:ascii-string-to-byte-array \"%s\") (ironclad:ascii-string-to-byte-array \"%s\") %d %d))"
			.formatted(digest, password, salt, iterations, keyLength);
		return eval(form).display();
	}

	// --- interception fired (the "dead flag" guard) ------------------------------

	@Test
	void loadingIroncladReplacesPbkdf2DeriveKeyWithTheNative() {
		// An ironclad defun is a LispLambda ("#<lambda>"); the installed kernel is a
		// native LispFunction. This is the only assertion in the file that fails if the
		// install never reaches loadSystem.
		assertThat(eval("#'ironclad::pbkdf2-derive-key").print())
			.isEqualTo("#<function " + IroncladNative.PBKDF2_DERIVE_KEY + ">");
	}

	// --- published vectors -------------------------------------------------------

	@Test
	void pbkdf2MatchesThePublishedSha256Vectors() {
		// RFC 7914 section 11's PBKDF2-HMAC-SHA-256 cases, including the multi-block
		// derived key (40 > 32 bytes, so the outer block loop runs twice).
		assertThat(deriveKey(":sha256", "password", "salt", 1, 32))
			.isEqualTo("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b");
		assertThat(deriveKey(":sha256", "password", "salt", 2, 32))
			.isEqualTo("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43");
		assertThat(deriveKey(":sha256", "password", "salt", 4096, 32))
			.isEqualTo("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a");
		assertThat(deriveKey(":sha256", "passwordPASSWORDpassword", "saltSALTsaltSALTsaltSALTsaltSALTsalt", 4096, 40))
			.isEqualTo("348c89dbcbd32b2f32d814b8116e84cf2b17347ebc1800181c4e2a1fb8dd53e1c635518c7dac47e9");
	}

	@Test
	void pbkdf2MatchesThePublishedSha224Vector() {
		assertThat(deriveKey(":sha224", "password", "salt", 4096, 28))
			.isEqualTo("218c453bf90635bd0a21a75d172703ff6108ef603f65bb821aedade1");
	}

	@Test
	void ironcladsOwnSymbolNamesTheSameDigestAsTheKeyword() {
		assertThat(deriveKey("'ironclad::sha256", "password", "salt", 4096, 32))
			.isEqualTo(deriveKey(":sha256", "password", "salt", 4096, 32));
	}

	// --- the JDK as an independent oracle ----------------------------------------

	@ValueSource(ints = { 0, 1, 55, 56, 57, 63, 64, 65, 119, 120, 128, 1000 })
	@ParameterizedTest
	void theDigestMatchesTheJdkAcrossEveryPaddingBoundary(int length) throws Exception {
		// The message lengths that decide which padding branch runs: a length whose
		// remainder is 56..63 pushes the length field into a SECOND block.
		byte[] message = new byte[length];
		for (int i = 0; i < length; i++) {
			message[i] = (byte) (i * 31 + 7);
		}
		assertThat(Sha2Kernels.digest(false, message)).isEqualTo(MessageDigest.getInstance("SHA-256").digest(message));
		assertThat(Sha2Kernels.digest(true, message)).isEqualTo(MessageDigest.getInstance("SHA-224").digest(message));
	}

	@Test
	void pbkdf2MatchesTheJdkIncludingAKeyLongerThanTheHmacBlock() throws Exception {
		// A passphrase past the 64-byte HMAC block length is hashed down before padding
		// -- the one branch of the key schedule the published vectors never take.
		for (String password : new String[] { "", "pencil", "x".repeat(64), "y".repeat(65), "z".repeat(200) }) {
			byte[] salt = "QSXCR+Q6sek8bf92".getBytes(StandardCharsets.US_ASCII);
			byte[] expected = jdkPbkdf2("PBKDF2WithHmacSHA256", password, salt, 4096, 32);
			assertThat(Sha2Kernels.pbkdf2(false, password.getBytes(StandardCharsets.US_ASCII), salt, 4096, 32))
				.as(password)
				.isEqualTo(expected);
		}
	}

	@Test
	void pbkdf2MatchesTheJdkForADerivedKeySpanningManyBlocks() throws Exception {
		byte[] salt = "salt".getBytes(StandardCharsets.US_ASCII);
		for (int keyLength : new int[] { 1, 31, 32, 33, 64, 100 }) {
			byte[] expected = jdkPbkdf2("PBKDF2WithHmacSHA256", "pencil", salt, 17, keyLength);
			assertThat(Sha2Kernels.pbkdf2(false, "pencil".getBytes(StandardCharsets.US_ASCII), salt, 17, keyLength))
				.as("key length %d", keyLength)
				.isEqualTo(expected);
		}
	}

	private byte[] jdkPbkdf2(String algorithm, String password, byte[] salt, int iterations, int keyLength)
			throws Exception {
		PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyLength * 8);
		return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded();
	}

	// --- the declined inputs stay ironclad's -------------------------------------

	@Test
	void anUnsupportedDigestFallsBackToIroncladsOwnError() {
		// SHA-1 is outside both the kernel and the loadable slice: the native must
		// DECLINE rather than answer, so ironclad's own message is what surfaces.
		assertThat(errorFrom(":sha1", 2)).isEqualTo("Digest SHA1 is not a supported digest.");
	}

	@Test
	void anOutOfRangeIterationCountFallsBackToIroncladsOwnCheckType() {
		assertThat(errorFrom(":sha256", 0))
			.isEqualTo("The value of IRONCLAD::ITERATION-COUNT is 0, which is not of type (INTEGER 1 *).");
	}

	@Test
	void aBoxedSaltDerivesTheSameKeyAsThePackedOne() {
		// cl-base64 and friends hand over general vectors; the answer may not depend on
		// which representation reaches the kernel.
		String boxed = eval(
				"(ironclad:byte-array-to-hex-string (ironclad::pbkdf2-derive-key :sha256 (ironclad:ascii-string-to-byte-array \"password\") (vector 115 97 108 116) 4096 32))")
			.display();
		assertThat(boxed).isEqualTo(deriveKey(":sha256", "password", "salt", 4096, 32));
	}

	private String errorFrom(String digest, int iterations) {
		String form = "(handler-case (ironclad::pbkdf2-derive-key %s (ironclad:ascii-string-to-byte-array \"p\") (ironclad:ascii-string-to-byte-array \"s\") %d 32) (error (e) (format nil \"~a\" e)))"
			.formatted(digest, iterations);
		return eval(form).display();
	}

	@Test
	void theScramSaltedPasswordAgreesWithTheJdk() throws Exception {
		// The RFC 7677 section 3 exchange cl-postgres runs: password "pencil", the
		// server's base64 salt, 4096 rounds. The full ClientProof chain from here is
		// IroncladE2eTest's, on all four backends.
		byte[] salt = Base64.getDecoder().decode("W22ZaJ0SNY7soEsUEjb6gQ==");
		assertThat(Sha2Kernels.pbkdf2(false, "pencil".getBytes(StandardCharsets.US_ASCII), salt, 4096, 32))
			.isEqualTo(jdkPbkdf2("PBKDF2WithHmacSHA256", "pencil", salt, 4096, 32));
	}

}
