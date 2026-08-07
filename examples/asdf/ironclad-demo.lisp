;; Loads the SHA-256 / HMAC / PBKDF2 / HKDF slice of the REAL ironclad (BSD 3-Clause,
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
(print
 (ironclad:byte-array-to-hex-string
  (ironclad:digest-sequence
   :sha256 (ironclad:ascii-string-to-byte-array "abc"))))
(print
 (ironclad:byte-array-to-hex-string
  (ironclad:digest-sequence :sha256 (ironclad:ascii-string-to-byte-array
                                     "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))))

;; SHA-224 shares the SHA-256 compression function
(print
 (ironclad:byte-array-to-hex-string
  (ironclad:digest-sequence
   :sha224 (ironclad:ascii-string-to-byte-array "abc"))))

;; RFC 4231 test case 2: HMAC-SHA-256 through the make-mac API
(let ((mac
       (ironclad:make-mac :hmac (ironclad:ascii-string-to-byte-array "Jefe")
                          :sha256)))
  (ironclad:update-mac mac
   (ironclad:ascii-string-to-byte-array "what do ya want for nothing?"))
  (print (ironclad:byte-array-to-hex-string (ironclad:produce-mac mac))))

;; RFC 5869 test case 1: HKDF-SHA-256 (make-kdf :hmac-kdf), whose output builder
;; concatenates its blocks into a '(vector (unsigned-byte 8))
(let ((kdf
       (ironclad:make-kdf :hmac-kdf :digest
        :sha256 :additional-data
        (ironclad:hex-string-to-byte-array "f0f1f2f3f4f5f6f7f8f9"))))
  (print
   (ironclad:byte-array-to-hex-string
    (ironclad:derive-key kdf
     (ironclad:hex-string-to-byte-array
      "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
     (ironclad:hex-string-to-byte-array "000102030405060708090a0b0c") 1 42))))

;; PBKDF2-HMAC-SHA-256, 4096 iterations
(let ((kdf (ironclad:make-kdf :pbkdf2 :digest :sha256)))
  (print
   (ironclad:byte-array-to-hex-string
    (ironclad:derive-key kdf (ironclad:ascii-string-to-byte-array "password")
                         (ironclad:ascii-string-to-byte-array "salt") 4096
                         32))))

;; SCRAM-SHA-256 (RFC 7677 section 3), the sequence a PostgreSQL client runs to
;; authenticate: SaltedPassword -> ClientKey -> StoredKey -> ClientSignature ->
;; ClientProof. The proof step XORs two 32-byte digests as 256-bit INTEGERS, so
;; it needs arbitrary-precision exact integers -- which every backend has.
(defun scram-hmac (key message)
  (ironclad:hmac-digest
   (ironclad:update-hmac (ironclad:make-hmac key :sha256)
                         (ironclad:ascii-string-to-byte-array message))))

;; integer-to-octets returns the MINIMAL vector, so a proof whose high bytes
;; cancel comes back shorter than 32 and has to be padded back on the left.
(defun pad-octet-vector (vector desired-length)
  (let ((length (length vector)))
    (if (= desired-length length)
        vector
        (replace (make-array desired-length
                             :element-type '(unsigned-byte 8)
                             :initial-element 0) vector
                 :start1 (- desired-length length)))))

(let* ((salted
        (ironclad:pbkdf2-hash-password
         (ironclad:ascii-string-to-byte-array "pencil")
         :salt (ironclad:hex-string-to-byte-array
                "5b6d99689d12358eeca04b141236fa81")
         :digest :sha256
         :iterations 4096))
       (nonce "rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0")
       (auth-message
        (concatenate 'string "n=user,r=rOprNGfwEbeRWgbNEkqO,r=" nonce
                     ",s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096,c=biws,r=" nonce))
       (client-key (scram-hmac salted "Client Key"))
       (stored-key (ironclad:digest-sequence :sha256 client-key))
       (client-signature (scram-hmac stored-key auth-message)))
  (print (ironclad:byte-array-to-hex-string salted))
  (print
   (ironclad:byte-array-to-hex-string
    (pad-octet-vector (ironclad:integer-to-octets
                       (logxor (ironclad:octets-to-integer client-key)
                               (ironclad:octets-to-integer client-signature)))
                      32))))
