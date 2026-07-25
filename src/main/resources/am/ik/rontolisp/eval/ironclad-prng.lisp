;; A leaf-module shim replacing ironclad's src/prng/prng.lisp.
;;
;; The real file is a Fortuna CSPRNG plus seed-file/FIFO plumbing over
;; /dev/urandom and four per-implementation Windows entropy calls -- none of
;; which exists here. What the slice actually needs is the OS-entropy surface:
;; `*prng*', `make-prng', `random-data', `random-bits' and `strong-random'
;; (SCRAM's client nonce is `(crypto:strong-random 62)' per character), plus
;; `random-data' as the never-taken default of `pbkdf2-hash-password''s :salt.
;;
;; It is REAL, not a stub: every byte comes from `rontolisp:random-bytes', the
;; cryptographic entropy primitive that exists on all four backends
;; (java.security.SecureRandom on the interpreter and the JVM, the WASI
;; random_get host function -- wasi:random under --component -- on both wasm
;; backends). That is what retires the earlier signalling stub, which existed
;; only because no cross-backend entropy source did.
;;
;; Deliberate narrowing: :fortuna and :fortuna-generator are not implemented,
;; so `make-prng' accepts any name and always returns the OS generator, and
;; the seed-file operations (`read-seed'/`write-seed'/`prng-reseed') are absent
;; -- an OS generator needs no seeding, and every caller in the slice passes
;; :os. `strong-random' keeps upstream's rejection sampling, so its
;; distribution is uniform (a modulo fold would bias the low values).
;;
;; Written in canonical shape (qualified names, no in-package): the leaf-module
;; splice bypasses the package-resolution bracketing a loaded file gets.

(defparameter ironclad:*prng* :os)

(defun ironclad:list-all-prngs () (list :os))

(defmethod ironclad:make-prng (name &rest %icp-args)
  (declare (ignore name %icp-args))
  ;; A DEFMETHOD, not a defun: ironclad's generic.lisp already declares
  ;; (defgeneric make-prng (name &key seed)), and a defun of the same name
  ;; would emit a SECOND method of that name into the compiled class
  ;; (ClassFormatError: duplicate method). Unspecialized = the generic's
  ;; default method, which is what every caller here reaches. The OS generator
  ;; is the only one, and it carries no state: the designator IS the generator.
  :os)

(defun ironclad:random-data (num-bytes &optional prng)
  (declare (ignore prng))
  (rontolisp:random-bytes num-bytes))

(defun ironclad:random-bits (num-bits &optional prng)
  (declare (ignore prng))
  (logand (- (expt 2 num-bits) 1)
          (ironclad:octets-to-integer (rontolisp:random-bytes (ceiling num-bits 8)))))

(defun ironclad:strong-random (limit &optional prng)
  (declare (ignore prng))
  ;; Rejection sampling over whole bytes, as upstream: draw integer-length bits
  ;; and retry while the draw is >= limit, so every value below limit is
  ;; equally likely.
  (if (<= limit 0)
      (error "ironclad:strong-random expects a positive limit")
      (let* ((%icp-bits (integer-length limit))
             (%icp-bytes (ceiling %icp-bits 8))
             (%icp-mask (- (ash 1 %icp-bits) 1))
             (%icp-value nil))
        (while (null %icp-value)
          (let ((%icp-draw (logand (ironclad:octets-to-integer
                                    (rontolisp:random-bytes %icp-bytes))
                                   %icp-mask)))
            (if (< %icp-draw limit) (setq %icp-value %icp-draw) nil)))
        %icp-value)))
