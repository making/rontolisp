package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the SHA-2 / HMAC / PBKDF2 /
 * RSA slice of the REAL ironclad v0.61 sources (vendored unmodified under
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
 * B), SHA-224 of {@code "abc"}, SHA-384 and SHA-512 of {@code "abc"} and of the 1024-bit
 * block two-block message (FIPS 180-2 appendices C and D), HMAC-SHA-256/384/512 of RFC
 * 4231 test case 2 (SHA-256 through BOTH the {@code make-mac} API and the deprecated
 * {@code make-hmac} one), HKDF-SHA-256 of RFC 5869 test case 1 (the {@code hmac-kdf}),
 * and PBKDF2-HMAC-SHA-256 at 1 and 4096 iterations.
 *
 * <p>
 * The RSA stack ({@code src/math.lisp} +
 * {@code public-key/}{@code {public-key,pkcs1,rsa}.lisp}) is exercised from a FIXED
 * 2048-bit key pair, so the signature is a constant rather than a fresh key per run:
 * {@code make-private-key} / {@code make-public-key} -> {@code destructure-private-key}
 * round trip -> a raw {@code sign-message} whose 256-byte value is pinned (it is
 * {@code m^d mod n} of the SHA-256 digest of {@code "abc"}, independently reproducible)
 * -> {@code
 * verify-signature} accepting it and rejecting a different message. PSS and OAEP draw a
 * random salt resp. seed, so those legs assert the ROUND TRIP instead: sign/verify under
 * {@code :pss :sha256} and {@code :pss :sha512}, encrypt/decrypt under {@code :oaep
 * :sha256}. A closing {@code generate-key-pair :rsa :num-bits 1024} proves the generator
 * itself -- it draws its primes from the prng shim's {@code rontolisp:random-bytes}, so
 * its output cannot be pinned, only its shape and its own sign/verify round trip. (PSS
 * with SHA-512 needs >= 1040 modulus bits: ironclad's own {@code (>= num-bytes (+ (* 2
 * digest-len) 2))} assertion. That is upstream's constraint, not a rontolisp limit.)
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
			(print (ironclad:byte-array-to-hex-string
			        (ironclad:digest-sequence :sha384 (ironclad:ascii-string-to-byte-array "abc"))))
			(print (ironclad:byte-array-to-hex-string
			        (ironclad:digest-sequence :sha512 (ironclad:ascii-string-to-byte-array "abc"))))
			(print (ironclad:byte-array-to-hex-string
			        (ironclad:digest-sequence :sha384
			          (ironclad:ascii-string-to-byte-array
			           "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"))))
			(print (ironclad:byte-array-to-hex-string
			        (ironclad:digest-sequence :sha512
			          (ironclad:ascii-string-to-byte-array
			           "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"))))
			(let ((mac (ironclad:make-mac :hmac (ironclad:ascii-string-to-byte-array "Jefe") :sha256)))
			  (ironclad:update-mac mac (ironclad:ascii-string-to-byte-array "what do ya want for nothing?"))
			  (print (ironclad:byte-array-to-hex-string (ironclad:produce-mac mac))))
			(let ((hmac (ironclad:make-hmac (ironclad:ascii-string-to-byte-array "Jefe") :sha256)))
			  (ironclad:update-hmac hmac (ironclad:ascii-string-to-byte-array "what do ya want for nothing?"))
			  (print (ironclad:byte-array-to-hex-string (ironclad:hmac-digest hmac))))
			(let ((mac (ironclad:make-mac :hmac (ironclad:ascii-string-to-byte-array "Jefe") :sha384)))
			  (ironclad:update-mac mac (ironclad:ascii-string-to-byte-array "what do ya want for nothing?"))
			  (print (ironclad:byte-array-to-hex-string (ironclad:produce-mac mac))))
			(let ((mac (ironclad:make-mac :hmac (ironclad:ascii-string-to-byte-array "Jefe") :sha512)))
			  (ironclad:update-mac mac (ironclad:ascii-string-to-byte-array "what do ya want for nothing?"))
			  (print (ironclad:byte-array-to-hex-string (ironclad:produce-mac mac))))
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
			;;; RSA over a FIXED 2048-bit key pair (generated once with openssl, carried
			;;; as hex so the signature below is a constant instead of a fresh key each
			;;; run). The private exponent and both primes are the real ones, so
			;;; destructure-private-key has something to give back.
			(defparameter *rsa-n* (ironclad:octets-to-integer (ironclad:hex-string-to-byte-array "d01c708f91a9038f62a5fd55ce3d1454857a220f92b33c4fb8c1b86840b6064099088053a3be5a1aeda9c54fb0c44b7d373bd097f282ab99e4b8fed626aafc41739597981387370ca20abe05839567c21422b42392eba320e4cafe0bece676420cbbe501a36cd19b9947bf18f5d6708651a3d5286085ff42cbd16d76573cc166486fabcdad197ce8756a905309d87c30a07e4c313a3c721b1e25b4cbc2f9c1e94275ee7eabc37a987bd646aa4c4d04ed4bea3912e4fd2980f9486352cc5282a5ffac929a6430e3cef133e1af96562de2abecd6a39c03dc30f5237fff1f67a45243d32ee53d8f4c2aa8febdda3e3953fcca0f762f0c24ac6ea88c047a80a2e245")))
			(defparameter *rsa-e* 65537)
			(defparameter *rsa-d* (ironclad:octets-to-integer (ironclad:hex-string-to-byte-array "04cb4eed83c3c2bcfc1f0a4bbe7d4a395b3cd1c98d8ddafb142cc448848b1cf042863f5c8de65df1865d9599cd1eec85452f37d234483dd744fd5d03866f0472268d40e98433a67940476292c271ffeaa8e796c246096f1fdc1d70064ace1155dab0be6910007b00ac628a7cb2f71e6efdb4fa3d5ca1e19c42913fc60ce2edaa98a8266dc28bc5bbe1ff7a2aa61c604dce677c50e6b5b00cc560724afa64a23915bb78476f42b348a55cb33312e0712b084f24a85d2fe8ea425028757145430b38522048f1463c4a538d640c8caf109a15e8df2c0cbf4df93fc3e957734b92bbf18f577aedc5ad34de6a82dbf14b3ab601f719aed8422df046c3bfd79653fa51")))
			(defparameter *rsa-p* (ironclad:octets-to-integer (ironclad:hex-string-to-byte-array "eb2c8839840d4f35cce78ab14c64997a2b8c302b9f1564fe889d55e0a3bec5778c8120be4837a266a22fe7cf18e6e804af4114436ee9907aec7546ce8ab5b49e95bffea5b0ed3082f7b979b5889d7a922dffc196614ec6a7f9982c2172034e74f9e29a35b1d6f4884d4dabf7cda8ec289e69bb0ed7b10b18306f9999d6c54471")))
			(defparameter *rsa-q* (ironclad:octets-to-integer (ironclad:hex-string-to-byte-array "e28a624a5c96f64430a747e8feb038fd781359618c4cf27f3d9e785dac756c7d3facfdc1252a81c908db79e5d3f9498d023337a17e4106113ebbaa571cfe8c3db28619c5e3f111b0fa13c293a3b96a3fbed58a92bb8f059640bde1d098ff70868c9297c6dc49a9a74137b325f15c8f77793f49f3684abb54bd3443fd80c71515")))
			(defparameter *rsa-private* (ironclad:make-private-key :rsa :d *rsa-d* :n *rsa-n* :p *rsa-p* :q *rsa-q*))
			(defparameter *rsa-public* (ironclad:make-public-key :rsa :e *rsa-e* :n *rsa-n*))
			(defparameter *rsa-message* (ironclad:digest-sequence :sha256 (ironclad:ascii-string-to-byte-array "abc")))
			(let ((elements (ironclad:destructure-private-key *rsa-private*)))
			  (print (and (= (getf elements :n) *rsa-n*) (= (getf elements :d) *rsa-d*)
			              (= (getf elements :p) *rsa-p*) (= (getf elements :q) *rsa-q*))))
			(print (= (* *rsa-p* *rsa-q*) *rsa-n*))
			;;; Raw RSA: deterministic, so the signature itself is the assertion.
			(defparameter *rsa-signature* (ironclad:sign-message *rsa-private* *rsa-message*))
			(print (ironclad:byte-array-to-hex-string *rsa-signature*))
			(print (ironclad:verify-signature *rsa-public* *rsa-message* *rsa-signature*))
			(print (ironclad:verify-signature *rsa-public*
			                                  (ironclad:digest-sequence :sha256
			                                    (ironclad:ascii-string-to-byte-array "abd"))
			                                  *rsa-signature*))
			;;; PSS and OAEP draw a random salt resp. seed from the prng shim, so what is
			;;; pinnable is the round trip, not the bytes.
			(let ((signature (ironclad:sign-message *rsa-private* *rsa-message* :pss :sha256)))
			  (print (length signature))
			  (print (ironclad:verify-signature *rsa-public* *rsa-message* signature :pss :sha256)))
			(let ((signature (ironclad:sign-message *rsa-private* *rsa-message* :pss :sha512)))
			  (print (ironclad:verify-signature *rsa-public* *rsa-message* signature :pss :sha512)))
			(let ((ciphertext (ironclad:encrypt-message *rsa-public*
			                                            (ironclad:ascii-string-to-byte-array "attack at dawn")
			                                            :oaep :sha256)))
			  (print (length ciphertext))
			  (print (ironclad:byte-array-to-hex-string
			          (ironclad:decrypt-message *rsa-private* ciphertext :oaep :sha256))))
			;;; generate-key-pair draws its primes from the prng shim; only the shape and
			;;; the round trip can be asserted.
			(multiple-value-bind (private public) (ironclad:generate-key-pair :rsa :num-bits 1024)
			  (let ((signature (ironclad:sign-message private *rsa-message*)))
			    (print (integer-length (getf (ironclad:destructure-private-key private) :n)))
			    (print (length signature))
			    (print (ironclad:verify-signature public *rsa-message* signature))
			    (print (ironclad:verify-signature public
			                                      (ironclad:digest-sequence :sha256
			                                        (ironclad:ascii-string-to-byte-array "abd"))
			                                      signature))))
			""";

	private static final List<String> EXPECTED = List.of(
			"\"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\"",
			"\"248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1\"",
			"\"23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7\"",
			"\"cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7\"",
			"\"ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f\"",
			"\"09330c33f71147e83d192fc782cd1b4753111b173b3b05d22fa08086e3b0f712fcc7c71a557e2db966c3e9fa91746039\"",
			"\"8e959b75dae313da8cf4f72814fc143f8f7779c6eb9f7fa17299aeadb6889018501d289e4900f7e4331b99dec4b5433ac7d329eeb6dd26545e96e55b874be909\"",
			"\"5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843\"",
			"\"5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843\"",
			"\"af45d2e376484031617f78d2b58a6b1b9c7ef464f5a01b47e42ec3736322445e8e2240ca5e69e2c78b3239ecfab21649\"",
			"\"164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea2505549758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737\"",
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
			"\"00007c0000000000000000000000000000000000000000000000000000000001\"",
			// RSA over the fixed 2048-bit key: the destructure round trip, p*q = n, the
			// raw signature (m^d mod n of SHA-256("abc"), reproducible outside ironclad),
			// the accept and the reject.
			"T", "T",
			"\"5f426084a5dce88a30458ba8b685b1859896e07edb52960e8a456daf6e4c54d543c53706b9c5aa0b41b3867dd6c0d9d206fb6bc6436e8eaaeef4d976f58937e904fedf961044a6d8fc26457786c60d95560b2265afd6e123f28650ea33445d29ba735d366c426b5435a3c9964be9245f7c6514d439b2cc9f1d8e4844d896db8337eb1c007b8626e89132ae33d2ab0d430e676ace203d42404b5dfae1faa5caec7e9a4b0fd467b9d96660b839da5bf5d4189d5d0bfc3885941abdaa2d08550045b34fd46bc032d795d1bf3bf2e5bee1cdb2d77471507363bedb240b3ad40063a8139a2d13d8d6e1305ce293edba7201f530dad58ad402ff4ef62682b88186bf01\"",
			"T", "NIL",
			// PSS-SHA-256 (signature length, then the verify), PSS-SHA-512, then OAEP:
			// the ciphertext length and the decrypted plaintext as hex ("attack at
			// dawn").
			"256", "T", "T", "256", "\"61747461636b206174206461776e\"",
			// generate-key-pair :rsa :num-bits 1024 -- modulus bits, signature length,
			// accept, reject.
			"1024", "128", "T", "NIL");

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
