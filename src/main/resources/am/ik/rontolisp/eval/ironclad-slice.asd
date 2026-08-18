;;;; Hand-authored replacement for ironclad's executable ironclad.asd.
;;;;
;;;; The real ironclad.asd cannot be parsed as plain data (defclass on
;;;; cl-source-file, a defmacro generating defsystems, perform methods), so
;;;; this file redeclares -- in the supported defsystem subset -- the slice of
;;;; subsystems rontolisp can actually load. Component paths resolve against the
;;;; directory of the located ironclad.asd, so the REAL library sources are
;;;; loaded. The bordeaux-threads dependency is dropped: it has zero call sites
;;;; in this slice (it serves the Fortuna prng, which the prng shim replaces).
;;;;
;;;; IN: the reduced core, the SHA-256 and SHA-384/SHA-512 digests, the HMAC
;;;; mac, the HKDF/PBKDF2 kdfs, and the RSA public-key stack (src/math.lisp plus
;;;; public-key/{public-key,pkcs1,rsa}.lisp) -- generate-key-pair, sign-message,
;;;; verify-signature, encrypt-message/decrypt-message, with and without
;;;; PSS/OAEP.
;;;;
;;;; STILL OUT, each for its own reason: the ciphers, the aead modes and
;;;; octet-stream (no consumer); the Fortuna CSPRNG and os-prng (replaced by the
;;;; prng shim, which draws rontolisp:random-bytes on all four backends -- that
;;;; is the entropy RSA key generation and the PSS salt consume); the other
;;;; public-key algorithms (DSA, ElGamal, the elliptic curves, ed25519/ed448),
;;;; which rsa.lisp loads without and each of which is its own consumer
;;;; question; and the KDFs whose classes live in those subsystems (scrypt,
;;;; argon2, bcrypt).
;;;;
;;;; The aggregate "ironclad" system is defined as this slice, so a
;;;; :depends-on ("ironclad") (cl-postgres, uuid) resolves to the loadable
;;;; subset.

(defsystem "ironclad/core"
  :components ((:module "src"
                        :serial t
                        :components ((:file "package") (:file "conditions")
                                     (:file "generic") (:file "macro-utils")
                                     (:file "util") (:file "common")
                                     (:module "digests"
                                              :serial t
                                              :components ((:file "digest")))
                                     (:module "macs"
                                              :serial t
                                              :components ((:file "mac")))
                                     ;; prng/prng.lisp is an ironclad/core component in the
                                     ;; real .asd too and is kept here in the same position --
                                     ;; but as a LEAF-MODULE substitution (eval/ShimLibraries):
                                     ;; the real file is the Fortuna CSPRNG over /dev/urandom
                                     ;; and four per-implementation Windows entropy calls. The
                                     ;; shim is real, not a stub: every byte comes from
                                     ;; rontolisp:random-bytes, which exists on all four
                                     ;; backends. public-key/public-key.lisp, the real .asd's
                                     ;; next core component, moves DOWN to the RSA subsystem
                                     ;; below -- the core has no caller for its converters, and
                                     ;; splitting it from math.lisp buys nothing.
                                     (:module "prng"
                                              :serial t
                                              :components ((:file "prng")))))))

(defsystem "ironclad/digest/sha256"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :components ((:module "digests"
                                      :components ((:file "sha256")))))))

;;; sha512.lisp defines SHA-384 and SHA-512 both (one 64-bit compression
;;; function, two initial-state/truncation pairs), exactly as the real .asd's
;;; single "sha512" subsystem does.
(defsystem "ironclad/digest/sha512"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :components ((:module "digests"
                                      :components ((:file "sha512")))))))

(defsystem "ironclad/mac/hmac"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                :components ((:module "macs" :components ((:file "hmac")))))))

(defsystem "ironclad/kdf/pkcs5"
  :depends-on ("ironclad/core" "ironclad/mac/hmac")
  :components ((:module "src"
                :components ((:module "kdf" :components ((:file "pkcs5")))))))

;;; kdf.lisp (make-kdf) is loaded LAST, not with the core as real ASDF does: it
;;; make-instances every KDF class, and the compile paths expand a make-instance
;;; where it stands, so the classes it names must already be registered -- which is
;;; why the HKDF file below is a dependency of "ironclad/kdfs" rather than a sibling.
;;; A branch naming a class from a subsystem outside this slice (scrypt, argon2,
;;; bcrypt) compiles to a runtime error instead -- those KDFs are simply not
;;; available here.
(defsystem "ironclad/kdf/hmac"
  :depends-on ("ironclad/core" "ironclad/mac/hmac")
  :components ((:module "src"
                :components ((:module "kdf" :components ((:file "hmac")))))))

;;; password-hash.lisp is the real file, unmodified: its pbkdf2-hash-password is
;;; the entry point cl-postgres' SCRAM-SHA-256 authentication calls.
(defsystem "ironclad/kdf/password-hash"
  :depends-on ("ironclad/core" "ironclad/digest/sha256" "ironclad/kdf/pkcs5")
  :components ((:module "src"
                        :components ((:module "kdf"
                                      :components ((:file "password-hash")))))))

(defsystem "ironclad/kdfs"
  :depends-on ("ironclad/core" "ironclad/kdf/pkcs5" "ironclad/kdf/hmac")
  :components ((:module "src"
                :components ((:module "kdf" :components ((:file "kdf")))))))

;;; The RSA public-key stack. src/math.lisp (the bignum primitives expt-mod /
;;; generate-prime / modular-inverse-with-blinding) is an ironclad/core
;;; component in the real .asd; it is declared here instead, with the three
;;; public-key files it exists for, so a program that only wants digests does
;;; not compile it. The load order inside is real ASDF's own
;;; (public-key -> pkcs1 -> rsa) and the kdf.lisp question does not arise: no
;;; file here make-instances a class defined in another one -- rsa.lisp defines
;;; rsa-public-key/rsa-private-key and make-instances them itself, and
;;; public-key.lisp's only class (discrete-logarithm-group) is instantiated
;;; solely by the DSA/ElGamal files, which are out of the slice.
(defsystem "ironclad/public-key/rsa"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :serial t
                        :components ((:file "math")
                                     (:module "public-key"
                                              :serial t
                                              :components
                                              ((:file "public-key")
                                               (:file "pkcs1")
                                               (:file "rsa")))))))

(defsystem "ironclad"
  :depends-on ("ironclad/core" "ironclad/digest/sha256" "ironclad/digest/sha512"
               "ironclad/mac/hmac" "ironclad/kdf/pkcs5" "ironclad/kdf/hmac"
               "ironclad/kdf/password-hash" "ironclad/kdfs"
               "ironclad/public-key/rsa"))
