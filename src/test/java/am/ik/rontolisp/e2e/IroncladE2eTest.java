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
 * {@code make-mac} API and the deprecated {@code make-hmac} one, HKDF-SHA-256 of RFC 5869
 * test case 1 (the {@code hmac-kdf}), and PBKDF2-HMAC-SHA-256 at 1 and 4096 iterations.
 *
 * <p>
 * The exercise then runs RFC 7677 section 3 end to end -- {@code pbkdf2-hash-password} ->
 * ClientKey -> StoredKey -> ClientSignature -> ClientProof -- in the exact shape
 * cl-postgres' {@code scram.lisp} uses, because that is what makes this slice the
 * SCRAM-SHA-256 authentication dependency. The proof step is the interesting one: it XORs
 * two 32-byte digests as 256-bit INTEGERS ({@code octets-to-integer} / {@code logxor} /
 * {@code integer-to-octets}), so it only works on the wasm backends because exact
 * integers there are arbitrary-precision ({@code .kb/wasm-bignum.md}). A final synthetic
 * pair pins the leading-zero edge: {@code integer-to-octets} returns the minimal vector,
 * so a proof whose high bytes cancel comes back short and must be padded to 32 -- an
 * off-by-one there is a silently wrong proof that surfaces only as an auth failure.
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
			(let ((kdf (ironclad:make-kdf :hmac-kdf :digest :sha256
			                              :additional-data (ironclad:hex-string-to-byte-array "f0f1f2f3f4f5f6f7f8f9"))))
			  (print (ironclad:byte-array-to-hex-string
			          (ironclad:derive-key kdf
			                               (ironclad:hex-string-to-byte-array
			                                "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
			                               (ironclad:hex-string-to-byte-array "000102030405060708090a0b0c")
			                               1 42))))
			(let ((kdf (ironclad:make-kdf :pbkdf2 :digest :sha256)))
			  (print (ironclad:byte-array-to-hex-string
			          (ironclad:derive-key kdf (ironclad:ascii-string-to-byte-array "password")
			                               (ironclad:ascii-string-to-byte-array "salt") 1 32)))
			  (print (ironclad:byte-array-to-hex-string
			          (ironclad:derive-key kdf (ironclad:ascii-string-to-byte-array "password")
			                               (ironclad:ascii-string-to-byte-array "salt") 4096 32))))
			;;; SCRAM-SHA-256, RFC 7677 section 3: the exact sequence cl-postgres'
			;;; scram.lisp runs, from the password to the client proof.
			(defun scram-hmac (key message)
			  (ironclad:hmac-digest
			   (ironclad:update-hmac (ironclad:make-hmac key :sha256)
			                         (ironclad:ascii-string-to-byte-array message))))
			(defun pad-octet-vector (vector desired-length)
			  (let ((length (length vector)))
			    (if (= desired-length length)
			        vector
			        (replace (make-array desired-length :element-type '(unsigned-byte 8) :initial-element 0)
			                 vector :start1 (- desired-length length)))))
			(defun client-proof (client-key client-signature)
			  (pad-octet-vector (ironclad:integer-to-octets
			                     (logxor (ironclad:octets-to-integer client-key)
			                             (ironclad:octets-to-integer client-signature)))
			                    32))
			(let* ((salted (ironclad:pbkdf2-hash-password
			                (ironclad:ascii-string-to-byte-array "pencil")
			                :salt (ironclad:hex-string-to-byte-array "5b6d99689d12358eeca04b141236fa81")
			                :digest :sha256 :iterations 4096))
			       (nonce "rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0")
			       (auth-message (concatenate 'string
			                                  "n=user,r=rOprNGfwEbeRWgbNEkqO,r=" nonce
			                                  ",s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096,c=biws,r=" nonce))
			       (client-key (scram-hmac salted "Client Key"))
			       (stored-key (ironclad:digest-sequence :sha256 client-key))
			       (client-signature (scram-hmac stored-key auth-message)))
			  (print (ironclad:byte-array-to-hex-string salted))
			  (print (ironclad:byte-array-to-hex-string client-key))
			  (print (ironclad:byte-array-to-hex-string stored-key))
			  (print (ironclad:byte-array-to-hex-string client-signature))
			  (print (ironclad:byte-array-to-hex-string (client-proof client-key client-signature))))
			;;; The leading-zero edge: integer-to-octets returns the MINIMAL vector, so a
			;;; proof whose high bytes cancel comes back short -- which is the whole reason
			;;; cl-postgres pads back to 32. Getting this wrong yields a silently wrong
			;;; proof that only surfaces as an authentication failure.
			(let ((a (ironclad:hex-string-to-byte-array
			          "5a5a0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e"))
			      (b (ironclad:hex-string-to-byte-array
			          "5a5a7d02030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1f")))
			  (print (ironclad:byte-array-to-hex-string
			          (ironclad:integer-to-octets (logxor (ironclad:octets-to-integer a)
			                                              (ironclad:octets-to-integer b)))))
			  (print (ironclad:byte-array-to-hex-string (client-proof a b))))
			""";

	private static final List<String> EXPECTED = List.of(
			"\"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\"",
			"\"248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1\"",
			"\"23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7\"",
			"\"5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843\"",
			"\"5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843\"",
			"\"3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865\"",
			"\"120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b\"",
			"\"c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a\"",
			// SaltedPassword, ClientKey, StoredKey, ClientSignature, ClientProof --
			// RFC 7677 section 3 (the proof is that message's p= value, base64-decoded:
			// dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=).
			"\"c4a49510323ab4f952cac1fa99441939e78ea74d6be81ddf7096e87513dc615d\"",
			"\"a60fc923d67e8644a92d16b96eda5ef4656b0c725c484374be25535576996e8b\"",
			"\"586e5df283e6dceb5c3e791d8b8528ec191e664045ce971792e2e6b5bb13e2a6\"",
			"\"d27312467c28a40a8a7f05c73c0de33eb3cbfb4a83783b58144cf19ac6be1bdf\"",
			"\"747cdb65aa56224e2352137e52d7bdcad6a0f738df30782caa69a2cfb0277554\"",
			// The unpadded round trip loses the two leading zero bytes (30 bytes back
			// out of 32 in); padding restores them.
			"\"7c0000000000000000000000000000000000000000000000000000000001\"",
			"\"00007c0000000000000000000000000000000000000000000000000000000001\"");

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
