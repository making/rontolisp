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
;;;; IN: the reduced core, every digest but three (below), the HMAC mac, the
;;;; HKDF/PBKDF2 kdfs, "ironclad/prngs" over the prng shim, and the RSA
;;;; public-key stack (src/math.lisp plus public-key/{pkcs1,rsa}.lisp)
;;;; -- generate-key-pair, sign-message, verify-signature,
;;;; encrypt-message/decrypt-message, with and without PSS/OAEP.
;;;;
;;;; STILL OUT, each for its own reason: the ciphers, the aead modes and
;;;; octet-stream (no consumer); the digests sha3 (its keccak-rounds macros
;;;; macroexpand a dotimes-unrolled symbol-macro through &environment, which a
;;;; rontolisp macro receives as nil), kupyna and skein (each needs a cipher);
;;;; the Fortuna CSPRNG and os-prng (replaced by the
;;;; prng shim, which draws rontolisp:random-bytes on all four backends -- that
;;;; is the entropy RSA key generation and the PSS salt consume); the other
;;;; public-key algorithms (DSA, ElGamal, the elliptic curves, ed25519/ed448),
;;;; which rsa.lisp loads without and each of which is its own consumer
;;;; question; and the KDFs whose classes live in those subsystems (scrypt,
;;;; argon2, bcrypt).
;;;;
;;;; The aggregate "ironclad" system is the slice's original surface plus SHA-1
;;;; and MD5, so a :depends-on ("ironclad") (cl-postgres, uuid -- whose v5/v3
;;;; UUIDs digest with SHA-1/MD5) resolves to the loadable subset. The other
;;;; digests stay reachable by their own subsystem names only: every one would
;;;; otherwise be compiled into every program depending on "ironclad".

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
                                     ;; backends.
                                     (:module "prng"
                                              :serial t
                                              :components ((:file "prng")))
                                     ;; A core component in the real .asd too: its
                                     ;; octets-to-integer is what the prng's random-bits and
                                     ;; strong-random fold their bytes with.
                                     (:module "public-key"
                                      :serial t
                                      :components ((:file "public-key")))))))

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

;;; The other digests, one subsystem each as in the real .asd (the 32-bit Tiger
;;; variant: +tiger-wordsize+ is 64 only under #+(and sbcl x86-64)).
;;; md5-lispworks-int32.lisp reads to nothing outside its LispWorks feature.
(defsystem "ironclad/digest/adler32"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :components ((:module "digests"
                                      :components ((:file "adler32")))))))

(defsystem "ironclad/digest/blake2"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :components ((:module "digests"
                                      :components ((:file "blake2")))))))

(defsystem "ironclad/digest/blake2s"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :components ((:module "digests"
                                      :components ((:file "blake2s")))))))

(defsystem "ironclad/digest/crc24"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :components ((:module "digests"
                                              :components ((:file "crc24")))))))

(defsystem "ironclad/digest/crc32"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :components ((:module "digests"
                                              :components ((:file "crc32")))))))

(defsystem "ironclad/digest/groestl"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :components ((:module "digests"
                                      :components ((:file "groestl")))))))

(defsystem "ironclad/digest/jh"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                :components ((:module "digests" :components ((:file "jh")))))))

(defsystem "ironclad/digest/md2"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                :components ((:module "digests" :components ((:file "md2")))))))

(defsystem "ironclad/digest/md4"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                :components ((:module "digests" :components ((:file "md4")))))))

(defsystem "ironclad/digest/md5"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :components ((:module "digests"
                                              :serial t
                                              :components
                                              ((:file "md5")
                                               (:file
                                                "md5-lispworks-int32")))))))

(defsystem "ironclad/digest/ripemd-128"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :components ((:module "digests"
                                      :components ((:file "ripemd-128")))))))

(defsystem "ironclad/digest/ripemd-160"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :components ((:module "digests"
                                      :components ((:file "ripemd-160")))))))

(defsystem "ironclad/digest/sha1"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :components ((:module "digests"
                                              :components ((:file "sha1")))))))

(defsystem "ironclad/digest/sm3"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                :components ((:module "digests" :components ((:file "sm3")))))))

(defsystem "ironclad/digest/streebog"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :components ((:module "digests"
                                      :components ((:file "streebog")))))))

(defsystem "ironclad/digest/tiger"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :components ((:module "digests"
                                              :components ((:file "tiger")))))))

(defsystem "ironclad/digest/tree-hash"
  :depends-on ("ironclad/core" "ironclad/digest/tiger")
  :components ((:module "src"
                        :components ((:module "digests"
                                      :components ((:file "tree-hash")))))))

(defsystem "ironclad/digest/whirlpool"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :components ((:module "digests"
                                      :components ((:file "whirlpool")))))))

;;; The real "ironclad/prngs" aggregates Fortuna, which this slice does not
;;; implement; the OS generator it also provides lives in the core's prng shim.
;;; Declared so a :depends-on ("ironclad/prngs") (frugal-uuid's strong-random,
;;; cl-smtp through it) resolves to what the slice has.
(defsystem "ironclad/prngs" :depends-on ("ironclad/core"))

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
;;; generate-prime / modular-inverse-with-blinding) and public-key/pkcs1.lisp are
;;; ironclad/core components in the real .asd; they are declared here instead,
;;; with the rsa.lisp they exist for, so a program that only wants digests does
;;; not compile them. The load order inside is real ASDF's own
;;; (pkcs1 -> rsa) and the kdf.lisp question does not arise: rsa.lisp defines
;;; rsa-public-key/rsa-private-key and make-instances them itself.
(defsystem "ironclad/public-key/rsa"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                        :serial t
                        :components ((:file "math")
                                     (:module "public-key"
                                              :serial t
                                              :components
                                              ((:file "pkcs1")
                                               (:file "rsa")))))))

(defsystem "ironclad"
  :depends-on ("ironclad/core" "ironclad/digest/sha256" "ironclad/digest/sha512"
               "ironclad/digest/sha1" "ironclad/digest/md5" "ironclad/prngs"
               "ironclad/mac/hmac" "ironclad/kdf/pkcs5" "ironclad/kdf/hmac"
               "ironclad/kdf/password-hash" "ironclad/kdfs"
               "ironclad/public-key/rsa"))
