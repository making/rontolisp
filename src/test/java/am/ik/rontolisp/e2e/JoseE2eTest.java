package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): Eitaro Fukamachi's jose -- a
 * JSON Object Signing and Encryption / JWT implementation (BSD 2-Clause, quicklisp dist
 * {@code jose-20250622-git}, vendored UNMODIFIED under {@code src/test/resources/jose})
 * -- loads via {@code asdf:load-system} and signs, verifies and decodes identically on
 * all four backends via {@link AsdfLibraryE2eSupport}. Upstream's own test suite is
 * {@link JoseTestSuiteE2eTest}.
 *
 * <p>
 * jose is a {@code :class :package-inferred-system}, so its four files load off their own
 * {@code defpackage} headers with no {@code :components} list, and every dependency loads
 * unpatched too: cl-json, cl-base64, ironclad, split-sequence, assoc-utils, alexandria
 * and trivial-utf-8 (the last over the built-in {@code mgl-pax-bootstrap} shim).
 *
 * <p>
 * Every line below was verified against SBCL 2.2.9 running the SAME upstream sources, and
 * the HMAC tokens additionally against Python's {@code hmac}/{@code hashlib}: the HS256
 * signature is the one jose's own README publishes. That matters because a JWT is only
 * worth anything if a DIFFERENT implementation accepts it, so the oracle here is
 * deliberately not ourselves.
 *
 * <p>
 * What the exercise covers, in order: HS256/HS384/HS512 encode -&gt; decode -&gt;
 * {@code inspect-token} (the claims, the headers and the raw signature, without
 * verifying); the unsecured {@code :none} token; integer {@code iat}/{@code nbf}/{@code
 * exp} claims together with the {@code :issuer}/{@code :audience}/{@code :subject}
 * checks; every claim check that signals -- a non-integer {@code iat}, an expired {@code
 * exp} and an {@code nbf} in the future, the last two through {@code cerror} so a {@code
 * handler-bind} that continues resumes the decode; a wrong key ({@code
 * jws-verification-error}, correctable the same way) and two malformed tokens ({@code
 * jws-invalid-format}); then the RSA family over a FIXED 2048-bit key pair --
 * RS256/384/512 are deterministic PKCS#1 v1.5, so the token itself is the assertion,
 * while PS256/384/512 draw a random salt and can only pin the round trip.
 *
 * <p>
 * The integer claims are the load-bearing half of {@code decode}: every one of jose's
 * claim checks is an {@code integerp} guard, so the JSON decoder has to answer an INTEGER
 * rather than the string it would fall back to. The exercise uses {@code handler-case},
 * so it compiles in EH mode on both WASM legs.
 */
class JoseE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "jose").toAbsolutePath().toString();

	private static final String EXERCISE = """
			(asdf:load-system :jose)

			(defparameter *secret* (ironclad:ascii-string-to-byte-array "my$ecret"))

			;;; HMAC: the token is a pure function of the claims, so it is the assertion.
			;;; The HS256 signature is the one jose's own README publishes, and all three
			;;; match Python's hmac/hashlib byte for byte.
			(dolist (algorithm '(:hs256 :hs384 :hs512))
			  (let ((token (jose:encode algorithm *secret* '(("hello" . "world")))))
			    (print token)
			    (print (jose:decode algorithm *secret* token))
			    (multiple-value-bind (claims headers signature) (jose:inspect-token token)
			      (print claims)
			      (print headers)
			      (print (length signature)))))

			;;; The unsecured token: an empty signature, and :none verifies only emptiness.
			(let ((token (jose:encode :none nil '(("a" . "b")))))
			  (print token)
			  (print (jose:decode :none nil token)))

			;;; Integer claims: the JSON decoder has to answer an INTEGER here or every one
			;;; of jose's claim checks signals (each is an integerp guard).
			(let* ((now (- (get-universal-time) 2208988800))
			       (token (jose:encode :hs256 *secret*
			                           `(("iss" . "rontolisp") ("aud" . "example") ("sub" . "42")
			                             ("jti" . "id-1")
			                             ("iat" . ,(- now 10)) ("nbf" . ,(- now 10)) ("exp" . ,(+ now 3600))))))
			  (multiple-value-bind (claims headers)
			      (jose:decode :hs256 *secret* token :issuer "rontolisp" :audience '("other" "example")
			                                         :subject "42")
			    (print (mapcar #'car claims))
			    (print (cdr (assoc "iss" claims :test #'string=)))
			    (print (integerp (cdr (assoc "exp" claims :test #'string=))))
			    (print headers))
			  (print (handler-case (progn (jose:decode :hs256 *secret* token :issuer "someone-else") :no-error)
			           (jose/errors:jwt-claims-error () :jwt-claims-error))))

			;;; The claim checks that signal: a non-integer iat, an exp in the past and an
			;;; nbf in the future. The last two go through cerror, so a handler-bind that
			;;; continues resumes the decode -- which is how a caller ignores them.
			(print (handler-case (jose:decode :hs256 *secret*
			                                  (jose:encode :hs256 *secret* '(("iat" . "not-a-number"))))
			         (jose/errors:jwt-claims-error () :jwt-claims-error)))
			(let* ((now (- (get-universal-time) 2208988800))
			       (expired (jose:encode :hs256 *secret* `(("exp" . ,(- now 1000)))))
			       (early (jose:encode :hs256 *secret* `(("nbf" . ,(+ now 1000))))))
			  (print (handler-case (jose:decode :hs256 *secret* expired)
			           (jose/errors:jwt-claims-expired () :jwt-claims-expired)))
			  (print (mapcar #'car (handler-bind ((jose/errors:jwt-claims-expired #'continue))
			                         (jose:decode :hs256 *secret* expired))))
			  (print (handler-case (jose:decode :hs256 *secret* early)
			           (jose/errors:jwt-claims-not-yet-valid () :jwt-claims-not-yet-valid)))
			  (print (mapcar #'car (handler-bind ((jose/errors:jwt-claims-not-yet-valid #'continue))
			                         (jose:decode :hs256 *secret* early)))))

			;;; A wrong key and a malformed token.
			(let ((token (jose:encode :hs256 *secret* '(("hello" . "world")))))
			  (print (handler-case (jose:decode :hs256 (ironclad:ascii-string-to-byte-array "wrong") token)
			           (jose/errors:jws-verification-error () :jws-verification-error)))
			  (print (handler-bind ((jose/errors:jws-verification-error #'continue))
			           (jose:decode :hs256 (ironclad:ascii-string-to-byte-array "wrong") token))))
			(print (handler-case (jose:decode :hs256 *secret* "not.a.jwt")
			         (jose/errors:jws-invalid-format () :jws-invalid-format)))
			(print (handler-case (jose:inspect-token "onlyonepart")
			         (jose/errors:jws-invalid-format () :jws-invalid-format)))

			;;; RSA over a FIXED 2048-bit key pair, so RS* -- deterministic PKCS#1 v1.5 --
			;;; pins its token rather than a fresh key each run.
			(defparameter *rsa-n* (ironclad:octets-to-integer (ironclad:hex-string-to-byte-array "d01c708f91a9038f62a5fd55ce3d1454857a220f92b33c4fb8c1b86840b6064099088053a3be5a1aeda9c54fb0c44b7d373bd097f282ab99e4b8fed626aafc41739597981387370ca20abe05839567c21422b42392eba320e4cafe0bece676420cbbe501a36cd19b9947bf18f5d6708651a3d5286085ff42cbd16d76573cc166486fabcdad197ce8756a905309d87c30a07e4c313a3c721b1e25b4cbc2f9c1e94275ee7eabc37a987bd646aa4c4d04ed4bea3912e4fd2980f9486352cc5282a5ffac929a6430e3cef133e1af96562de2abecd6a39c03dc30f5237fff1f67a45243d32ee53d8f4c2aa8febdda3e3953fcca0f762f0c24ac6ea88c047a80a2e245")))
			(defparameter *rsa-e* 65537)
			(defparameter *rsa-d* (ironclad:octets-to-integer (ironclad:hex-string-to-byte-array "04cb4eed83c3c2bcfc1f0a4bbe7d4a395b3cd1c98d8ddafb142cc448848b1cf042863f5c8de65df1865d9599cd1eec85452f37d234483dd744fd5d03866f0472268d40e98433a67940476292c271ffeaa8e796c246096f1fdc1d70064ace1155dab0be6910007b00ac628a7cb2f71e6efdb4fa3d5ca1e19c42913fc60ce2edaa98a8266dc28bc5bbe1ff7a2aa61c604dce677c50e6b5b00cc560724afa64a23915bb78476f42b348a55cb33312e0712b084f24a85d2fe8ea425028757145430b38522048f1463c4a538d640c8caf109a15e8df2c0cbf4df93fc3e957734b92bbf18f577aedc5ad34de6a82dbf14b3ab601f719aed8422df046c3bfd79653fa51")))
			(defparameter *rsa-p* (ironclad:octets-to-integer (ironclad:hex-string-to-byte-array "eb2c8839840d4f35cce78ab14c64997a2b8c302b9f1564fe889d55e0a3bec5778c8120be4837a266a22fe7cf18e6e804af4114436ee9907aec7546ce8ab5b49e95bffea5b0ed3082f7b979b5889d7a922dffc196614ec6a7f9982c2172034e74f9e29a35b1d6f4884d4dabf7cda8ec289e69bb0ed7b10b18306f9999d6c54471")))
			(defparameter *rsa-q* (ironclad:octets-to-integer (ironclad:hex-string-to-byte-array "e28a624a5c96f64430a747e8feb038fd781359618c4cf27f3d9e785dac756c7d3facfdc1252a81c908db79e5d3f9498d023337a17e4106113ebbaa571cfe8c3db28619c5e3f111b0fa13c293a3b96a3fbed58a92bb8f059640bde1d098ff70868c9297c6dc49a9a74137b325f15c8f77793f49f3684abb54bd3443fd80c71515")))
			(defparameter *rsa-private* (ironclad:make-private-key :rsa :d *rsa-d* :n *rsa-n* :p *rsa-p* :q *rsa-q*))
			(defparameter *rsa-public* (ironclad:make-public-key :rsa :e *rsa-e* :n *rsa-n*))

			(dolist (algorithm '(:rs256 :rs384 :rs512))
			  (let ((token (jose:encode algorithm *rsa-private* '(("hello" . "world")))))
			    (print token)
			    (print (jose:decode algorithm *rsa-public* token))))

			;;; PSS draws a random salt, so what is pinnable is the round trip.
			(dolist (algorithm '(:ps256 :ps384 :ps512))
			  (let ((token (jose:encode algorithm *rsa-private* '(("hello" . "world")))))
			    (print (jose:decode algorithm *rsa-public* token))))
			""";

	private static final List<String> EXPECTED = List.of(
			"\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJoZWxsbyI6IndvcmxkIn0.jnuv3lQEhhNGtjLRHXGwKFIq8VrmW7Dr_jndXWHcBmU\"",
			"((\"hello\" . \"world\"))", "((\"hello\" . \"world\"))", "((\"alg\" . \"HS256\") (\"typ\" . \"JWT\"))",
			"32",
			"\"eyJhbGciOiJIUzM4NCIsInR5cCI6IkpXVCJ9.eyJoZWxsbyI6IndvcmxkIn0.kZnyZo_XTcKuSI8NBZoGg_gkgF5wl9xw0dkeghDeWcJLoPFWekgsP5RjlBHDpQko\"",
			"((\"hello\" . \"world\"))", "((\"hello\" . \"world\"))", "((\"alg\" . \"HS384\") (\"typ\" . \"JWT\"))",
			"48",
			"\"eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJoZWxsbyI6IndvcmxkIn0.gJSBWfJ_1lAw1iDdnA_IAYmAqgDe22gsttIs9zYceNTv9dRwttysPCoDlXasKVy-I-Xynue8Q6sbf613hsHosA\"",
			"((\"hello\" . \"world\"))", "((\"hello\" . \"world\"))", "((\"alg\" . \"HS512\") (\"typ\" . \"JWT\"))",
			"64", "\"eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJhIjoiYiJ9.\"", "((\"a\" . \"b\"))",
			"(\"exp\" \"nbf\" \"iat\" \"jti\" \"sub\" \"aud\" \"iss\")", "\"rontolisp\"", "T",
			"((\"alg\" . \"HS256\") (\"typ\" . \"JWT\"))", ":JWT-CLAIMS-ERROR", ":JWT-CLAIMS-ERROR",
			":JWT-CLAIMS-EXPIRED", "(\"exp\")", ":JWT-CLAIMS-NOT-YET-VALID", "(\"nbf\")", ":JWS-VERIFICATION-ERROR",
			"((\"hello\" . \"world\"))", ":JWS-INVALID-FORMAT", ":JWS-INVALID-FORMAT",
			"\"eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJoZWxsbyI6IndvcmxkIn0.YVkzhy5etqGjrWlW4AlpMa8Zs3GzQ1ahgoFf9Z9oH1KEkZsV1pIyLT-XfJCBqz5jzfrvUIODwrF4UyTqp9soA_mPCRA_mzmyvrjdDw2NE1YX-tYUBBFRWDyf5Znbr8W64kLfiLVZ_rTOCTRPAff2PiNQ94_gACLMIw5e7diNTZGysu2VmP0EjdUSCpDWrciDkGTF61F1Alk_JFxl7KmsevZVpMkGtH0nwzg1AlFP9hiQ5JHVJs25jTveo95osKrPTiV65c6jPRNNfGGBvmLcMe-cqi39rN6CwO_-XTr_UbtXqTBWeMy0qUDgFwiW1VVZu_VRCv8AU60TP6SSLBHdrQ\"",
			"((\"hello\" . \"world\"))",
			"\"eyJhbGciOiJSUzM4NCIsInR5cCI6IkpXVCJ9.eyJoZWxsbyI6IndvcmxkIn0.AkpUegDTsR9mlCD4UVp2xX4ipMvlaO6HsUE5dm8F5SPUYb_zheEGrfHQmsA67hTY-TLDweVQ6-NAdC0utcYljfyfcnzhK5gZbnHl7NfhEX9sEtH4SXjRbfN7i9hs5GoZfIcq05qeyGMJpyZGJOQ9qg0cmPoLa63zb2Tbbc4yj58oalhKH75bg-G280fuiSqMmX76jojthWNZX8zjzigQUNtVeG1L9ZXRgcio8zaAZr1ZjNGIezTFus5bOAhi2VSGRqCEmhWOsgQ6W_Olt4uwIjNsiOjYOlKY0CaREW5c1b9Umlw1R3mipbGan09E_IaVmjIbxAq8jhF-t7pu0BAjfA\"",
			"((\"hello\" . \"world\"))",
			"\"eyJhbGciOiJSUzUxMiIsInR5cCI6IkpXVCJ9.eyJoZWxsbyI6IndvcmxkIn0.YVGHhgWGk45O_U0pYUH3xCADCbY-dlEYviNyH2BG7xOqssFYAuF879d57U3UjE8P95xBw4_HDP15sfLZQtxC9mK3FY5LvWNtV9KNMjvH4XgMu1Nk8zXmKAL8JSVyICGGoCvvDUg-JQiAMscA-KbzqDR7Kg7WTk5QW6enKzsznleBS-O6U6zCtEtOtD-hNOF2XevI-kakOYYVmZlEDM2Q3Gv2tG_Ot2eVHJ7x9GNdjCNR7lcRTPvL681BiBhhgKzmYfqg_9eW-hO0xpTk-EVxqN-UhqRxpuBPsTbWzl1G1A7f8DaMu76Gv2wT-GXbnWddLKgzE0SYO9FxGEoB9wsHGw\"",
			"((\"hello\" . \"world\"))", "((\"hello\" . \"world\"))", "((\"hello\" . \"world\"))",
			"((\"hello\" . \"world\"))");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected List<String> extraSystemPath() {
		return JoseSystems.DEPENDENCY_PATH;
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
		return "JoseE2e";
	}

}
