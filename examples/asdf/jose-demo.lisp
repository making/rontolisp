;; jose (BSD 2-Clause, Eitaro Fukamachi) -- JSON Object Signing and Encryption
;; / JWT -- loaded via asdf:load-system from the REAL upstream sources. Run with:
;;   SYS=src/test/resources/jose:src/test/resources/cl-json:src/test/resources/ironclad:src/test/resources/cl-base64:src/test/resources/split-sequence:src/test/resources/assoc-utils:src/test/resources/alexandria:src/test/resources/trivial-utf-8
;;   rontolisp examples/asdf/jose-demo.lisp --system-path $SYS
;; Runs on all four backends. It uses handler-case.

(asdf:load-system :jose)

(defparameter *secret* (ironclad:ascii-string-to-byte-array "my$ecret"))

;; HS256. The token is a pure function of the claims, and this one is the very
;; token jose's own README publishes -- Python's hmac/hashlib agrees byte for
;; byte, which is the only thing that makes a JWT worth issuing.
(defparameter *token* (jose:encode :hs256 *secret* '(("hello" . "world"))))
(print *token*)

;; decode VERIFIES the signature, then hands back the claims and the headers.
(multiple-value-bind (claims headers) (jose:decode :hs256 *secret* *token*)
  (print claims)
  (print headers))

;; inspect-token does not verify. Its third value is the raw signature, which
;; for HS256 is the 32 bytes of the HMAC.
(multiple-value-bind (claims headers signature) (jose:inspect-token *token*)
  (print claims)
  (print headers)
  (print (length signature)))

;; HS384 and HS512 need SHA-384/512; :none is the unsecured token, whose
;; signature is the empty string.
(print (jose:encode :hs384 *secret* '(("hello" . "world"))))
(print (jose:encode :hs512 *secret* '(("hello" . "world"))))
(print (jose:encode :none nil '(("hello" . "world"))))

;; The registered claims. iat / nbf / exp must decode as INTEGERS -- every one
;; of jose's checks is an integerp guard -- and :issuer / :audience / :subject
;; are checked against the claims of the same name.
(defparameter *now* (- (get-universal-time) 2208988800))
(defparameter *claims-token*
  (jose:encode :hs256 *secret*
               `(("iss" . "rontolisp") ("aud" . "example") ("sub" . "42")
                 ("iat" . ,*now*) ("nbf" . ,*now*) ("exp" . ,(+ *now* 3600)))))
(print
 (mapcar #'car
         (jose:decode :hs256 *secret* *claims-token*
                      :issuer "rontolisp"
                      :audience "example"
                      :subject "42")))

;; A claim that fails its check signals. An expired exp goes through cerror, so
;; a handler-bind that continues decodes anyway -- that is how a caller says
;; "I know, give me the claims".
(defparameter *expired*
  (jose:encode :hs256 *secret* `(("exp" . ,(- *now* 1000)))))
(print
 (handler-case (jose:decode :hs256 *secret* *expired*)
   (jose/errors:jwt-claims-expired () :expired)))
(print
 (mapcar #'car
         (handler-bind ((jose/errors:jwt-claims-expired #'continue))
           (jose:decode :hs256 *secret* *expired*))))

;; The wrong key is a jws-verification-error (also correctable); a token that is
;; not three dot-separated parts is a jws-invalid-format.
(print
 (handler-case (jose:decode :hs256 (ironclad:ascii-string-to-byte-array "wrong")
                            *token*)
   (jose/errors:jws-verification-error () :bad-signature)))
(print
 (handler-case (jose:decode :hs256 *secret* "not.a.jwt")
   (jose/errors:jws-invalid-format () :malformed)))

;; RS256 over a fixed 2048-bit key pair. RSA keys are ironclad objects, so any
;; source of one works -- generate-key-pair, or a parser for a PEM file.
(defparameter *n*
  (ironclad:octets-to-integer
   (ironclad:hex-string-to-byte-array
    "d01c708f91a9038f62a5fd55ce3d1454857a220f92b33c4fb8c1b86840b6064099088053a3be5a1aeda9c54fb0c44b7d373bd097f282ab99e4b8fed626aafc41739597981387370ca20abe05839567c21422b42392eba320e4cafe0bece676420cbbe501a36cd19b9947bf18f5d6708651a3d5286085ff42cbd16d76573cc166486fabcdad197ce8756a905309d87c30a07e4c313a3c721b1e25b4cbc2f9c1e94275ee7eabc37a987bd646aa4c4d04ed4bea3912e4fd2980f9486352cc5282a5ffac929a6430e3cef133e1af96562de2abecd6a39c03dc30f5237fff1f67a45243d32ee53d8f4c2aa8febdda3e3953fcca0f762f0c24ac6ea88c047a80a2e245")))
(defparameter *d*
  (ironclad:octets-to-integer
   (ironclad:hex-string-to-byte-array
    "04cb4eed83c3c2bcfc1f0a4bbe7d4a395b3cd1c98d8ddafb142cc448848b1cf042863f5c8de65df1865d9599cd1eec85452f37d234483dd744fd5d03866f0472268d40e98433a67940476292c271ffeaa8e796c246096f1fdc1d70064ace1155dab0be6910007b00ac628a7cb2f71e6efdb4fa3d5ca1e19c42913fc60ce2edaa98a8266dc28bc5bbe1ff7a2aa61c604dce677c50e6b5b00cc560724afa64a23915bb78476f42b348a55cb33312e0712b084f24a85d2fe8ea425028757145430b38522048f1463c4a538d640c8caf109a15e8df2c0cbf4df93fc3e957734b92bbf18f577aedc5ad34de6a82dbf14b3ab601f719aed8422df046c3bfd79653fa51")))
(defparameter *private-key* (ironclad:make-private-key :rsa :d *d* :n *n*))
(defparameter *public-key* (ironclad:make-public-key :rsa :e 65537 :n *n*))

;; RS* is deterministic PKCS#1 v1.5, so this token is a constant.
(defparameter *rs-token*
  (jose:encode :rs256 *private-key* '(("hello" . "world"))))
(print *rs-token*)
(print (jose:decode :rs256 *public-key* *rs-token*))

;; PS* is RSA-PSS, which salts every signature -- what repeats is the round
;; trip, not the token.
(print
 (jose:decode :ps256 *public-key*
              (jose:encode :ps256 *private-key* '(("hello" . "world")))))
