;; The SHA-384/512 and RSA half of the REAL ironclad slice (BSD 3-Clause,
;; Nathan Froyd / Guillaume LE VAILLANT), loaded via asdf:load-system. Run with:
;;   rontolisp examples/asdf/ironclad-rsa-demo.lisp --system-path src/test/resources/ironclad
;; Runs on all four backends. Its sibling ironclad-demo.lisp covers the
;; SHA-256 / HMAC / PBKDF2 / HKDF / SCRAM half; this one loads the same real
;; sources for src/math.lisp and src/public-key/{public-key,pkcs1,rsa}.lisp.

(asdf:load-system :ironclad)

;; FIPS 180-2: SHA-384 and SHA-512 of "abc". One file defines both -- one
;; 64-bit compression function, two initial states.
(print
 (ironclad:byte-array-to-hex-string
  (ironclad:digest-sequence
   :sha384 (ironclad:ascii-string-to-byte-array "abc"))))
(print
 (ironclad:byte-array-to-hex-string
  (ironclad:digest-sequence
   :sha512 (ironclad:ascii-string-to-byte-array "abc"))))

;; RFC 4231 test case 2: HMAC-SHA-512
(let ((mac
       (ironclad:make-mac :hmac (ironclad:ascii-string-to-byte-array "Jefe")
                          :sha512)))
  (ironclad:update-mac mac
   (ironclad:ascii-string-to-byte-array "what do ya want for nothing?"))
  (print (ironclad:byte-array-to-hex-string (ironclad:produce-mac mac))))

;; A fixed 2048-bit RSA key pair, so the raw signature below is a CONSTANT --
;; it is m^d mod n of the SHA-256 digest of "abc", reproducible outside
;; ironclad with any bignum library.
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
(defparameter *message*
  (ironclad:digest-sequence
   :sha256 (ironclad:ascii-string-to-byte-array "abc")))

(defparameter *signature* (ironclad:sign-message *private-key* *message*))
(print (ironclad:byte-array-to-hex-string *signature*))
(print (ironclad:verify-signature *public-key* *message* *signature*))
;; ... and it rejects a signature over a different message.
(print
 (ironclad:verify-signature *public-key*
  (ironclad:digest-sequence :sha256 (ironclad:ascii-string-to-byte-array "abd"))
  *signature*))

;; PSS salts every signature, so what repeats is the round trip, not the bytes.
;; The salt comes from rontolisp:random-bytes, the entropy source every backend
;; has (ironclad's own Fortuna generator is outside the slice).
(let ((signature (ironclad:sign-message *private-key* *message* :pss :sha256)))
  (print
   (ironclad:verify-signature *public-key* *message* signature :pss :sha256)))

;; Key generation draws its primes from the same source.
(multiple-value-bind (private public)
    (ironclad:generate-key-pair :rsa :num-bits 1024)
  (let ((signature (ironclad:sign-message private *message*)))
    (print
     (integer-length (getf (ironclad:destructure-private-key private) :n)))
    (print (ironclad:verify-signature public *message* signature))))
