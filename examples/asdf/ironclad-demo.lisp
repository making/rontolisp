;; Loads the SHA-256 / HMAC / PBKDF2 slice of the REAL ironclad (BSD 3-Clause,
;; Nathan Froyd / Guillaume LE VAILLANT) via asdf:load-system and reproduces
;; published test vectors. Run with:
;;   rontolisp examples/asdf/ironclad-demo.lisp --system-path src/test/resources/ironclad
;; Runs on all four backends. ironclad's own ironclad.asd is an executable
;; program (component classes, a defsystem-generating macro), so rontolisp
;; substitutes a bundled replacement .asd declaring the loadable slice -- the
;; component files loaded are the library's real ones. Ciphers, public keys,
;; PRNGs and the other digests are outside the slice.

(asdf:load-system :ironclad)

;; FIPS 180-2: SHA-256 of "abc" and of a two-block message
(print (ironclad:byte-array-to-hex-string
        (ironclad:digest-sequence :sha256 (ironclad:ascii-string-to-byte-array "abc"))))
(print (ironclad:byte-array-to-hex-string
        (ironclad:digest-sequence :sha256
          (ironclad:ascii-string-to-byte-array
           "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))))

;; SHA-224 shares the SHA-256 compression function
(print (ironclad:byte-array-to-hex-string
        (ironclad:digest-sequence :sha224 (ironclad:ascii-string-to-byte-array "abc"))))

;; RFC 4231 test case 2: HMAC-SHA-256 through the make-mac API
(let ((mac (ironclad:make-mac :hmac (ironclad:ascii-string-to-byte-array "Jefe") :sha256)))
  (ironclad:update-mac mac (ironclad:ascii-string-to-byte-array "what do ya want for nothing?"))
  (print (ironclad:byte-array-to-hex-string (ironclad:produce-mac mac))))

;; PBKDF2-HMAC-SHA-256, 4096 iterations
(let ((kdf (ironclad:make-kdf :pbkdf2 :digest :sha256)))
  (print (ironclad:byte-array-to-hex-string
          (ironclad:derive-key kdf (ironclad:ascii-string-to-byte-array "password")
                               (ironclad:ascii-string-to-byte-array "salt") 4096 32)))
  ;; RFC 7677's SCRAM-SHA-256 SaltedPassword -- the value a PostgreSQL client
  ;; needs for SCRAM authentication
  (print (ironclad:byte-array-to-hex-string
          (ironclad:derive-key kdf (ironclad:ascii-string-to-byte-array "pencil")
                               (ironclad:hex-string-to-byte-array "5b6d99689d12358eeca04b141236fa81")
                               4096 32))))
