;;;; Hand-authored replacement for ironclad's executable ironclad.asd.
;;;;
;;;; The real ironclad.asd cannot be parsed as plain data (defclass on
;;;; cl-source-file, a defmacro generating defsystems, perform methods), so
;;;; this file redeclares -- in the supported defsystem subset -- the slice of
;;;; subsystems rontolisp can actually load: the reduced core (no ciphers, no
;;;; prng, no math, no octet streams, no aead, no public keys) plus the
;;;; SHA-256 digest, the HMAC mac and the HKDF/PBKDF2 kdfs. Component paths
;;;; resolve against the directory of the located ironclad.asd, so the REAL
;;;; library sources are loaded. The bordeaux-threads dependency is dropped:
;;;; it has zero call sites in this slice (it serves prng/).
;;;;
;;;; The aggregate "ironclad" system is defined as this slice, so a
;;;; :depends-on ("ironclad") (cl-postgres) resolves to the loadable subset.

(defsystem "ironclad/core"
  :components ((:module "src"
                :serial t
                :components ((:file "package")
                             (:file "conditions")
                             (:file "generic")
                             (:file "macro-utils")
                             (:file "util")
                             (:file "common")
                             (:module "digests"
                              :serial t
                              :components ((:file "digest")))
                             (:module "macs"
                              :serial t
                              :components ((:file "mac")))))))

(defsystem "ironclad/digest/sha256"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                :components ((:module "digests"
                              :components ((:file "sha256")))))))

(defsystem "ironclad/mac/hmac"
  :depends-on ("ironclad/core")
  :components ((:module "src"
                :components ((:module "macs"
                              :components ((:file "hmac")))))))

(defsystem "ironclad/kdf/pkcs5"
  :depends-on ("ironclad/core" "ironclad/mac/hmac")
  :components ((:module "src"
                :components ((:module "kdf"
                              :components ((:file "pkcs5")))))))

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
                :components ((:module "kdf"
                              :components ((:file "hmac")))))))

(defsystem "ironclad/kdfs"
  :depends-on ("ironclad/core" "ironclad/kdf/pkcs5" "ironclad/kdf/hmac")
  :components ((:module "src"
                :components ((:module "kdf"
                              :components ((:file "kdf")))))))

(defsystem "ironclad"
  :depends-on ("ironclad/core"
               "ironclad/digest/sha256"
               "ironclad/mac/hmac"
               "ironclad/kdf/pkcs5"
               "ironclad/kdf/hmac"
               "ironclad/kdfs"))
